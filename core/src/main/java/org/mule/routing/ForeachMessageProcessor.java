/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.routing;

import org.mule.DefaultMuleMessage;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.processor.InterceptingMessageProcessor;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.transformer.DataType;
import org.mule.api.transformer.Transformer;
import org.mule.api.transformer.TransformerException;
import org.mule.expression.ExpressionConfig;
import org.mule.processor.AbstractMessageProcessorOwner;
import org.mule.processor.chain.DefaultMessageProcessorChainBuilder;
import org.mule.routing.outbound.AbstractMessageSequenceSplitter;
import org.mule.routing.outbound.CollectionMessageSequence;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transformer.types.SimpleDataType;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;

/**
 * The <code>Foreach</code> MessageProcessor allows iterating over a collection
 * payload, or any collection obtained by an expression, generating a message for
 * each element.
 * <p>
 * The number of the message being processed is stored in
 * <code>#[variable:counter]</code> and the root message is store in
 * <code>#[variable:rootMessage]</code>. Both variables may be renamed by means of
 * {@link #setCounterVariableName(String)} and
 * {@link #setRootMessageVariableName(String)}.
 * <p>
 * Defining a groupSize greater than one, allows iterating over collections of
 * elements of the specified size.
 * <p>
 * The {@link MuleEvent} sent to the next message processor is the same that arrived
 * to foreach.
 */
public class ForeachMessageProcessor extends AbstractMessageProcessorOwner implements Initialisable, InterceptingMessageProcessor
{

    public static final String ROOT_MESSAGE_PROPERTY = "rootMessage";
    public static final String COUNTER_PROPERTY = "counter";
    private static final String XPATH_PREFIX = "#[xpath";

    protected Log logger = LogFactory.getLog(getClass());

    private List<MessageProcessor> messageProcessors;
    private MessageProcessor ownedMessageProcessor;
    private AbstractMessageSequenceSplitter splitter;
    private MessageProcessor next;
    private String collectionExpression;
    private int batchSize;
    private String rootMessageVariableName;
    private String counterVariableName;
    private Transformer xml2dom;
    private Transformer dom2xml;

    @Override
    public MuleEvent process(MuleEvent event) throws MuleException
    {
        String parentMessageProp = rootMessageVariableName != null ? rootMessageVariableName : ROOT_MESSAGE_PROPERTY;
        boolean transformed = transformPayloadIfNeeded(event);
        event.getMessage().setInvocationProperty(parentMessageProp, event.getMessage());
        ownedMessageProcessor.process(event);
        if (transformed)
        {
            transformBack(event);
        }
        return processNext(event);
    }

    private boolean transformPayloadIfNeeded(MuleEvent event) throws TransformerException
    {
        boolean transformed = false;
        MuleMessage message = event.getMessage();
        if (collectionExpression != null && collectionExpression.startsWith(XPATH_PREFIX) && message.getPayload() instanceof String)
        {
            message.setPayload(xml2dom.transform(message.getPayload()));
            transformed = true;
        }
        return transformed;
    }

    private void transformBack(MuleEvent event) throws TransformerException
    {
        event.getMessage().setPayload(dom2xml.transform(event.getMessage().getPayload()));
    }

    protected MuleEvent processNext(MuleEvent event) throws MuleException
    {
        if (next == null)
        {
            return event;
        }
        else
        {
            return next.process(event);
        }
    }

    @Override
    protected List<MessageProcessor> getOwnedMessageProcessors()
    {
        return messageProcessors;
    }

    @Override
    public void setListener(MessageProcessor listener)
    {
        next = listener;
    }

    public void setMessageProcessors(List<MessageProcessor> messageProcessors) throws MuleException
    {
        this.messageProcessors = messageProcessors;
    }

    @Override
    public void initialise() throws InitialisationException
    {
        if (collectionExpression != null)
        {
            ExpressionConfig config = new ExpressionConfig();
            config.setExpression(checkEvaluator(collectionExpression));
            splitter = new ExpressionSplitter(config);
            if (collectionExpression.startsWith(XPATH_PREFIX))
            {
                DataType<Document> docType = new SimpleDataType<Document>(Document.class);
                try
                {
                    xml2dom = muleContext.getRegistry().lookupTransformer(DataTypeFactory.XML_STRING, docType);
                    dom2xml = muleContext.getRegistry().lookupTransformer(docType, DataTypeFactory.XML_STRING);
                }
                catch (TransformerException e)
                {
                    throw new InitialisationException(e, this);
                }
            }
        }
        else
        {
            splitter = new CollectionMapSplitter();
        }
        splitter.setBatchSize(batchSize);
        splitter.setCounterVariableName(counterVariableName);
        splitter.setMuleContext(muleContext);
        messageProcessors.add(0, splitter);

        try
        {
            this.ownedMessageProcessor = new DefaultMessageProcessorChainBuilder().chain(messageProcessors).build();
        }
        catch (MuleException e)
        {
            throw new InitialisationException(e, this);
        }
        super.initialise();
    }

    private String checkEvaluator(String expression)
    {
        String result = expression;
        if (expression.startsWith(XPATH_PREFIX))
        {
            result = "#[xpath-branch" + expression.substring(expression.indexOf(':'));
        }
        return result;
    }

    public void setCollection(String expression)
    {
        this.collectionExpression = expression;
    }

    public void setBatchSize(int batchSize)
    {
        this.batchSize = batchSize;
    }

    public void setRootMessageVariableName(String rootMessageVariableName)
    {
        this.rootMessageVariableName = rootMessageVariableName;
    }

    public void setCounterVariableName(String counterVariableName)
    {
        this.counterVariableName = counterVariableName;
    }

    private static class CollectionMapSplitter extends CollectionSplitter
    {

        @Override
        protected MessageSequence<?> splitMessageIntoSequence(MuleEvent event)
        {
            Object payload = event.getMessage().getPayload();
            if (payload instanceof Map<?, ?>)
            {
                List<MuleMessage> list = new LinkedList<MuleMessage>();
                Set<Map.Entry<?, ?>> set = ((Map) payload).entrySet();
                for (Entry<?, ?> entry : set)
                {
                    MuleMessage splitMessage = new DefaultMuleMessage(entry.getValue(), muleContext);
                    splitMessage.setInvocationProperty(MapSplitter.MAP_ENTRY_KEY, entry.getKey());
                    list.add(splitMessage);
                }
                return new CollectionMessageSequence(list);
            }
            return super.splitMessageIntoSequence(event);
        }

    }
}


