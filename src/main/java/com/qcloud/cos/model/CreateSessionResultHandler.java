package com.qcloud.cos.model;

import com.qcloud.cos.Headers;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.CosHttpResponse;
import com.qcloud.cos.internal.AbstractCosResponseHandler;
import com.qcloud.cos.internal.CosServiceResponse;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 解析 CreateSession 返回的 XML 响应。
 */
public class CreateSessionResultHandler extends AbstractCosResponseHandler<CreateSessionResult> {

    private static final XMLInputFactory xmlInputFactory;
    static {
        xmlInputFactory = XMLInputFactory.newInstance();
        xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    @Override
    public CosServiceResponse<CreateSessionResult> handle(CosHttpResponse response) throws Exception {
        String requestId = response.getHeaders().get(Headers.REQUEST_ID);

        CreateSessionResult result = new CreateSessionResult();
        result.setRequestId(requestId);

        InputStream content = response.getContent();
        if (content != null) {
            parseXml(content, result);
        }
        validateResult(result);

        CosServiceResponse<CreateSessionResult> cosResponse = new CosServiceResponse<>();
        cosResponse.setResult(result);
        return cosResponse;
    }

    private void parseXml(InputStream in, CreateSessionResult result) {
        try {
            XMLStreamReader reader;
            synchronized (xmlInputFactory) {
                reader = xmlInputFactory.createXMLStreamReader(in);
            }
            try {
                CreateSessionResult.Credentials credentials = null;
                while (reader.hasNext()) {
                    int event = reader.next();
                    String tag;
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        tag = reader.getLocalName();
                        if ("Credentials".equals(tag)) {
                            credentials = new CreateSessionResult.Credentials();
                        } else if ("RequestId".equals(tag)) {
                            result.setRequestId(reader.getElementText().trim());
                        } else if (credentials != null) {
                            switch (tag) {
                                case "AccessKeyId": credentials.setTmpSecretId(reader.getElementText().trim()); break;
                                case "SecretAccessKey": credentials.setTmpSecretKey(reader.getElementText().trim()); break;
                                case "SessionToken": credentials.setSessionToken(reader.getElementText().trim()); break;
                                case "Expiration": result.setExpiration(reader.getElementText().trim()); break;
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "Credentials".equals(reader.getLocalName())) {
                        result.setCredentials(credentials);
                        credentials = null;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            throw new CosClientException("Failed to parse CreateSession XML response", e);
        }
    }

    private void validateResult(CreateSessionResult result) {
        CreateSessionResult.Credentials credentials = result.getCredentials();
        if (credentials == null
                || credentials.getTmpSecretId() == null || credentials.getTmpSecretId().isEmpty()
                || credentials.getTmpSecretKey() == null || credentials.getTmpSecretKey().isEmpty()
                || credentials.getSessionToken() == null || credentials.getSessionToken().isEmpty()) {
            throw new CosClientException("CreateSession returned invalid credentials, requestId="
                    + result.getRequestId());
        }
        if (result.getExpiration() == null || result.getExpiration().isEmpty()) {
            throw new CosClientException("CreateSession returned no valid expiration, requestId="
                    + result.getRequestId());
        }
        long expiredTimeSec;
        try {
            expiredTimeSec = Instant.parse(result.getExpiration()).getEpochSecond();
        } catch (DateTimeParseException e) {
            throw new CosClientException("CreateSession returned invalid expiration, requestId="
                    + result.getRequestId(), e);
        }
        if (expiredTimeSec <= System.currentTimeMillis() / 1000L) {
            throw new CosClientException("CreateSession returned expired credentials, requestId="
                    + result.getRequestId());
        }
    }
}
