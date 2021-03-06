/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.security.oauth.processor;

import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.callback.HttpCallback;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transformer.TransformerMessagingException;
import org.mule.common.security.oauth.AuthorizationParameter;
import org.mule.security.oauth.OAuth2Adapter;
import org.mule.security.oauth.OAuth2Manager;
import org.mule.security.oauth.OAuthProperties;
import org.mule.security.oauth.notification.OAuthAuthorizeNotification;
import org.mule.tck.size.SmallTest;

import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class OAuth2AuthorizeMessageProcessorTestCase
{

    private static final String eventId = "eventId";

    private OAuth2Manager<OAuth2Adapter> manager;
    private TestAuthorizeMessageProcessor processor;
    private MuleEvent event;

    @Mock
    private MuleContext muleContext;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp()
    {
        this.manager = Mockito.mock(OAuth2Manager.class, Mockito.RETURNS_DEEP_STUBS);
        this.event = Mockito.mock(MuleEvent.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(this.event.getId()).thenReturn(eventId);

        this.processor = new TestAuthorizeMessageProcessor();
        HttpCallback callback = Mockito.mock(HttpCallback.class);

        Mockito.when(callback.getUrl()).thenReturn("url");
        this.processor.setOauthCallback(callback);

        this.processor.setModuleObject(this.manager);
        this.processor.setMuleContext(this.muleContext);
    }

    @Test
    public void getOAuthManager()
    {
        Assert.assertSame(this.manager, this.processor.getOAuthManager());
    }

    @Test
    public void process() throws Exception
    {
        this.doProcess("state");
    }

    @Test
    public void processWithoutState() throws Exception
    {
        this.doProcess("");
    }

    @SuppressWarnings("unchecked")
    private void doProcess(final String state) throws Exception
    {
        final String authorizeUrl = "authorizeUrl";
        final String customField = "customField";
        final String anotherCustomField = "anotherCustomField";

        this.processor.setState(state);
        this.processor.setCustomField(customField);
        this.processor.setAnotherCustomField(anotherCustomField);

        Mockito.when(this.manager.getDefaultUnauthorizedConnector().getAuthorizationParameters()).thenReturn(
            this.getAuthorizePropertiesWithoutDefaults());

        Mockito.when(
            this.manager.buildAuthorizeUrl(Mockito.anyMap(), Mockito.anyString(), Mockito.anyString()))
            .thenAnswer(new Answer<String>()
            {
                @Override
                public String answer(InvocationOnMock invocation) throws Throwable
                {
                    Map<String, String> parameters = (Map<String, String>) invocation.getArguments()[0];
                    String expectedState = String.format(OAuthProperties.EVENT_STATE_TEMPLATE, eventId)
                                           + state;
                    Assert.assertEquals(expectedState, URLDecoder.decode(parameters.get("state"), "UTF-8"));
                    Assert.assertEquals(customField.toLowerCase(), parameters.get("customField"));
                    Assert.assertEquals(anotherCustomField.toLowerCase(),
                        parameters.get("anotherCustomField"));

                    return authorizeUrl;
                }
            });

        this.processor.process(event);

        Mockito.verify(this.manager).storeAuthorizationEvent(this.event);
        Mockito.verify(this.event.getMessage()).setOutboundProperty(OAuthProperties.HTTP_STATUS, "302");
        Mockito.verify(this.event.getMessage()).setOutboundProperty(OAuthProperties.CALLBACK_LOCATION,
            authorizeUrl);
        Mockito.verify(this.muleContext).fireNotification(
            Mockito.argThat(new OAuthNotificationMatcher(
                OAuthAuthorizeNotification.OAUTH_AUTHORIZATION_BEGIN, this.event)));
    }

    private Set<AuthorizationParameter<?>> getAuthorizePropertiesWithoutDefaults()
    {
        Set<AuthorizationParameter<?>> parameters = new LinkedHashSet<AuthorizationParameter<?>>();

        parameters.add(new AuthorizationParameter<String>("customField", "A custom field", false, null,
            String.class));
        parameters.add(new AuthorizationParameter<String>("anotherCustomField", "anotherCustomField", false,
            null, String.class));

        return parameters;
    }

    @SuppressWarnings("unused")
    private class TestAuthorizeMessageProcessor extends
        BaseOAuth2AuthorizeMessageProcessor<OAuth2Manager<OAuth2Adapter>>
    {

        private String customField;
        private String anotherCustomField;

        @Override
        protected String getAuthCodeRegex()
        {
            return "\"access_token\"[ ]*:[ ]*\"([^\\\"]*)\"";
        }

        @Override
        protected Class<OAuth2Manager<OAuth2Adapter>> getOAuthManagerClass()
        {
            return null;
        }

        /**
         * Ok, this is cheating, but actually executing the real implementation of
         * this method requires a lot of mocking for a low ROI since it will be
         * deeply tested on the integration tests
         */
        @Override
        protected Object evaluateAndTransform(MuleContext muleContext,
                                              MuleEvent event,
                                              Type expectedType,
                                              String expectedMimeType,
                                              Object source)
            throws TransformerException, TransformerMessagingException
        {
            return source != null ? source.toString() : null;
        }

        /**
         * Ok, this is cheating, but actually executing the real implementation of
         * this method requires a lot of mocking for a low ROI since it will be
         * deeply tested on the integration tests
         */
        @Override
        protected Object evaluateAndTransform(MuleContext muleContext,
                                              MuleMessage muleMessage,
                                              Type expectedType,
                                              String expectedMimeType,
                                              Object source)
            throws TransformerException, TransformerMessagingException
        {
            return source != null ? source.toString() : null;
        }

        public String getCustomField()
        {
            return customField;
        }

        public void setCustomField(String customField)
        {
            this.customField = customField;
        }

        public String getAnotherCustomField()
        {
            return anotherCustomField;
        }

        public void setAnotherCustomField(String anotherCustomField)
        {
            this.anotherCustomField = anotherCustomField;
        }

    }
}
