/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.eventhubs.amqp;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.qpid.proton.amqp.UnsignedLong;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode;
import org.apache.qpid.proton.amqp.transport.SenderSettleMode;
import org.apache.qpid.proton.engine.BaseHandler;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.engine.EndpointState;

import com.microsoft.azure.eventhubs.StringUtil;

public class RequestResponseChannel implements IIOObject {

    private final Sender sendLink;
    private final Receiver receiveLink;
    private final String replyTo;
    private final HashMap<Object, IOperationResult<Message, Exception>> inflightRequests;
    private final AtomicLong requestId;
    private final AtomicInteger openRefCount;
    private final AtomicInteger closeRefCount;

    private IOperationResult<Void, Exception> onOpen;
    private IOperationResult<Void, Exception> onClose; // handles closeLink due to failures
    private IOperationResult<Void, Exception> onGraceFullClose; // handles intentional close

    public RequestResponseChannel(
            final String linkName,
            final String path,
            final Session session) {

        this.replyTo = path.replace("$", "") + "-client-reply-to";
        this.openRefCount = new AtomicInteger(2);
        this.closeRefCount = new AtomicInteger(2);
        this.inflightRequests = new HashMap<>();
        this.requestId = new AtomicLong(0);

        this.sendLink = session.sender(linkName + ":sender");
        final Target target = new Target();
        target.setAddress(path);
        this.sendLink.setTarget(target);
        sendLink.setSource(new Source());
        this.sendLink.setSenderSettleMode(SenderSettleMode.SETTLED);
        BaseHandler.setHandler(this.sendLink, new SendLinkHandler(new RequestHandler()));

        this.receiveLink = session.receiver(linkName + ":receiver");
        final Source source = new Source();
        source.setAddress(path);
        this.receiveLink.setSource(source);
        final Target receiverTarget = new Target();
        receiverTarget.setAddress(this.replyTo);
        this.receiveLink.setTarget(receiverTarget);
        this.receiveLink.setSenderSettleMode(SenderSettleMode.SETTLED);
        this.receiveLink.setReceiverSettleMode(ReceiverSettleMode.SECOND);
        BaseHandler.setHandler(this.receiveLink, new ReceiveLinkHandler(new ResponseHandler()));
    }

    // open should be called only once - we use FaultTolerantObject for that
    public void open(final IOperationResult<Void, Exception> onOpen, final IOperationResult<Void, Exception> onClose) {

        this.onOpen = onOpen;
        this.onClose = onClose;
        this.sendLink.open();
        this.receiveLink.open();
    }

    // close should be called exactly once - we use FaultTolerantObject for that
    public void close(final IOperationResult<Void, Exception> onGraceFullClose) {

        this.onGraceFullClose = onGraceFullClose;
        this.sendLink.close();
        this.receiveLink.close();
    }

    public Sender getSendLink() {
        return this.sendLink;
    }

    public Receiver getReceiveLink() {
        return this.receiveLink;
    }

    public void request(
            final ReactorDispatcher dispatcher,
            final Message message,
            final IOperationResult<Message, Exception> onResponse) {

        if (message == null)
            throw new IllegalArgumentException("message cannot be null");

        if (message.getMessageId() != null)
            throw new IllegalArgumentException("message.getMessageId() should be null");

        if (message.getReplyTo() != null)
            throw new IllegalArgumentException("message.getReplyTo() should be null");

        message.setMessageId("request" + UnsignedLong.valueOf(this.requestId.incrementAndGet()).toString());
        message.setReplyTo(this.replyTo);

        this.inflightRequests.put(message.getMessageId(), onResponse);

        try {
            dispatcher.invoke(new DispatchHandler() {
                @Override
                public void onEvent() {

                    final Delivery delivery = sendLink.delivery(UUID.randomUUID().toString().replace("-", StringUtil.EMPTY).getBytes());
                    final int payloadSize = AmqpUtil.getDataSerializedSize(message) + 512; // need buffer for headers

                    delivery.setContext(onResponse);

                    final byte[] bytes = new byte[payloadSize];
                    final int encodedSize = message.encode(bytes, 0, payloadSize);

                    receiveLink.flow(1);
                    sendLink.send(bytes, 0, encodedSize);
                    sendLink.advance();
                }
            });
        } catch (IOException ioException) {
            onResponse.onError(ioException);
        }
    }

    private void onLinkOpenComplete(final Exception exception) {

        if (openRefCount.decrementAndGet() <= 0 && onOpen != null)
            if (exception == null && this.sendLink.getRemoteState() == EndpointState.ACTIVE && this.receiveLink.getRemoteState() == EndpointState.ACTIVE)
                onOpen.onComplete(null);
            else {
                if (exception != null)
                    onOpen.onError(exception);
                else {
                    final ErrorCondition error = (this.sendLink.getRemoteCondition() != null && this.sendLink.getRemoteCondition().getCondition() != null)
                            ? this.sendLink.getRemoteCondition()
                            : this.receiveLink.getRemoteCondition();
                    onOpen.onError(new AmqpException(error));
                }
            }
    }

    private void onLinkCloseComplete(final Exception exception) {

        if (closeRefCount.decrementAndGet() <= 0)
            if (exception == null) {
                onClose.onComplete(null);
                if (onGraceFullClose != null)
                    onGraceFullClose.onComplete(null);
            } else {
                onClose.onError(exception);
                if (onGraceFullClose != null)
                    onGraceFullClose.onError(exception);
            }
    }

    @Override
    public IOObjectState getState() {

        if (sendLink.getLocalState() == EndpointState.UNINITIALIZED || receiveLink.getLocalState() == EndpointState.UNINITIALIZED
                || sendLink.getRemoteState() == EndpointState.UNINITIALIZED || receiveLink.getRemoteState() == EndpointState.UNINITIALIZED)
            return IOObjectState.OPENING;

        if (sendLink.getRemoteState() == EndpointState.ACTIVE && receiveLink.getRemoteState() == EndpointState.ACTIVE
                && sendLink.getLocalState() == EndpointState.ACTIVE && receiveLink.getRemoteState() == EndpointState.ACTIVE)
            return IOObjectState.OPENED;

        if (sendLink.getRemoteState() == EndpointState.CLOSED && receiveLink.getRemoteState() == EndpointState.CLOSED)
            return IOObjectState.CLOSED;

        return IOObjectState.CLOSING; // only left cases are if some are active and some are closed
    }

    private class RequestHandler implements IAmqpSender {

        @Override
        public void onFlow(int creditIssued) {
        }

        @Override
        public void onSendComplete(Delivery delivery) {
        }

        @Override
        public void onOpenComplete(Exception completionException) {

            onLinkOpenComplete(completionException);
        }

        @Override
        public void onError(Exception exception) {

            onLinkCloseComplete(exception);
        }

        @Override
        public void onClose(ErrorCondition condition) {

            if (condition == null || condition.getCondition() == null)
                onLinkCloseComplete(null);
            else
                onError(new AmqpException(condition));
        }

    }

    private class ResponseHandler implements IAmqpReceiver {

        @Override
        public void onReceiveComplete(Delivery delivery) {

            final Message response = Proton.message();
            final int msgSize = delivery.pending();
            final byte[] buffer = new byte[msgSize];

            final int read = receiveLink.recv(buffer, 0, msgSize);

            response.decode(buffer, 0, read);
            delivery.settle();

            final IOperationResult<Message, Exception> responseCallback = inflightRequests.remove(response.getCorrelationId());
            if (responseCallback != null)
                responseCallback.onComplete(response);
        }

        @Override
        public void onOpenComplete(Exception completionException) {

            onLinkOpenComplete(completionException);
        }

        @Override
        public void onError(Exception exception) {

            for (IOperationResult<Message, Exception> responseCallback : inflightRequests.values())
                responseCallback.onError(exception);

            inflightRequests.clear();

            if (onClose != null)
                onLinkCloseComplete(exception);
        }

        @Override
        public void onClose(ErrorCondition condition) {

            if (condition == null || condition.getCondition() == null)
                onLinkCloseComplete(null);
            else
                onError(new AmqpException(condition));
        }
    }
}
