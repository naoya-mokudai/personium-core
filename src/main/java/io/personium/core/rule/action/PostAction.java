/**
 * personium.io
 * Copyright 2017-2018 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.personium.core.rule.action;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpMessage;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;

import org.json.simple.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.personium.common.auth.token.Role;
import io.personium.common.utils.PersoniumCoreUtils;
import io.personium.core.event.PersoniumEvent;
import io.personium.core.model.Cell;
import io.personium.core.rule.ActionInfo;
import io.personium.core.utils.HttpClientFactory;

/**
 * Abstract class of Action about Post.
 */
public abstract class PostAction extends Action {
    static Logger logger = LoggerFactory.getLogger(PostAction.class);

    Cell cell;
    String service;
    String action;
    String eventId;
    String chain;

    /**
     * Constructor.
     * @param cell target cell object
     * @param ai ActionInfo object
     */
    public PostAction(Cell cell, ActionInfo ai) {
        this.cell = cell;
        this.service = ai.getService();
        this.action = ai.getAction();
        this.eventId = ai.getEventId();
        this.chain = ai.getRuleChain();
    }

    @Override
    public PersoniumEvent execute(PersoniumEvent event) {
        String requestUrl = getRequestUrl();
        if (requestUrl == null) {
            return null;
        }

        HttpClient client = HttpClientFactory.create(HttpClientFactory.TYPE_INSECURE);
        HttpPost req = new HttpPost(requestUrl);

        // create payload as JSON
        JSONObject json = createEvent(event);
        req.setEntity(new StringEntity(
                json.toString(),
                ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

        // set headers
        //  X-Personium-RequestKey, X-Personium-EventId, X-Personium-RuleChain, X-Personium-Via
        if (event.getRequestKey() != null) {
            req.addHeader(PersoniumCoreUtils.HttpHeaders.X_PERSONIUM_REQUESTKEY, event.getRequestKey());
        }
        req.addHeader(PersoniumCoreUtils.HttpHeaders.X_PERSONIUM_EVENTID, eventId);
        req.addHeader(PersoniumCoreUtils.HttpHeaders.X_PERSONIUM_RULECHAIN, chain);
        String via = getVia(event);
        if (via != null) {
            req.addHeader(PersoniumCoreUtils.HttpHeaders.X_PERSONIUM_VIA, via);
        }

        // set specific headers in derrived class
        setHeaders(req, event);

        HttpResponse objResponse = null;
        String result;
        try {
            objResponse = client.execute(req);
            logger.info(EntityUtils.toString(objResponse.getEntity()));
            result = Integer.toString(objResponse.getStatusLine().getStatusCode());
        } catch (ClientProtocolException e) {
            logger.error("Invalid Http response: " + e.getMessage(), e);
            result = "404";
        } catch (Exception e) {
            logger.error("Connection Error: " + e.getMessage(), e);
            result = "404";
        } finally {
            HttpClientUtils.closeQuietly(objResponse);
            HttpClientUtils.closeQuietly(client);
        }

        // create event for result of script execution
        PersoniumEvent evt = event.clone()
                .type(action)
                .object(service)
                .info(result)
                .eventId(eventId)
                .ruleChain(chain)
                .build();

        return evt;
    }

    @Override
    public PersoniumEvent execute(PersoniumEvent[] events) {
        // not supported
        return null;
    }

    /**
     * Create request url in derrived class.
     * @return created request url, null if error is occurred
     */
    protected abstract String getRequestUrl();

    /**
     * Create event in json format.
     * @param event PersoniumEvent object
     * @return json object
     */
    @SuppressWarnings({ "unchecked" })
    protected JSONObject createEvent(PersoniumEvent event) {
        JSONObject json = new JSONObject();

        json.put("External", event.getExternal());
        if (event.getSchema() != null) {
            json.put("Schema", event.getSchema());
        }
        if (event.getSubject() != null) {
            json.put("Subject", event.getSubject());
        }
        json.put("Type", event.getType());
        json.put("Object", event.getObject());
        json.put("Info", event.getInfo());

        return json;
    }

    /**
     * Set specific HTTP headers in derrived class.
     * @param req Request to set headers
     * @param event PersoniumEvent object
     */
    protected abstract void setHeaders(HttpMessage req, PersoniumEvent event);

    /**
     * Get Via header string.
     * @param event PersoniumEvent object
     * @return via header string
     */
    protected String getVia(PersoniumEvent event) {
        return event.getVia();
    }

    /**
     * Get permitted role list from event.
     * @param event PersoniumEvent object
     * @return role list
     */
    protected List<Role> getRoleList(PersoniumEvent event) {
        // create permitted role list
        List<Role> roleList = new ArrayList<Role>();
        String roles = event.getRoles();
        if (roles != null) {
            String[] parts = roles.split(",");
            for (int i = 0; i < parts.length; i++) {
                try {
                    URL url = new URL(parts[i]);
                    Role role = new Role(url);
                    roleList.add(role);
                } catch (MalformedURLException e) {
                    // return empty list because of error
                    return new ArrayList<Role>();
                }
            }
        }
        return roleList;
    }
}
