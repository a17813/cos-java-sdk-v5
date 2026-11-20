package com.qcloud.cos.rapid;

import com.qcloud.cos.model.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.*;

public class RapidBucketOperationTest extends AbstractRapidCOSClientTest {

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        // 禁止测试删除bucket
        AbstractRapidCOSClientTest.initRapidCosClient();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        AbstractRapidCOSClientTest.destroyRapidCosClient();
    }

    @Test
    public void testListObjects() throws IOException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }

        String key = "ut/rapid_list_test/" + System.currentTimeMillis();
        try {
            cosclient.putObject(new PutObjectRequest(bucket, key,
                    new ByteArrayInputStream("test".getBytes()), new ObjectMetadata()));

            ObjectListing listing = cosclient.listObjects(bucket, "ut/rapid_list_test/");
            assertNotNull(listing);
            assertTrue(listing.getObjectSummaries().size() > 0);
        } finally {
            clearObject(key);
        }
    }

    @Test
    public void testHeadBucket() {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }
        HeadBucketRequest headReq = new HeadBucketRequest(bucket);
        cosclient.headBucket(headReq);
    }

    @Test
    public void testDoesBucketExist() {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }
        assertTrue(cosclient.doesBucketExist(bucket));
    }

    @Test
    public void testCreateSession() {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }
        CreateSessionRequest req = new CreateSessionRequest(bucket);
        CreateSessionResult result = cosclient.createSession(req);
        assertNotNull(result);
        assertNotNull(result.getCredentials());
        assertNotNull(result.getCredentials().getTmpSecretId());
        assertNotNull(result.getCredentials().getTmpSecretKey());
        assertNotNull(result.getCredentials().getSessionToken());
        assertNotNull(result.getExpiration());
        long expiredTimeSec = Instant.parse(result.getExpiration()).getEpochSecond();
        assertTrue("expiration should be in the future",
                expiredTimeSec > System.currentTimeMillis() / 1000);
    }

    @Test
    public void testBucketPolicy() throws InterruptedException {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }

        String policyBucket = createTemporaryRapidBucket();
        String policyText = String.format("{"
                + "  \"Statement\": ["
                + "    {"
                + "      \"Principal\": {"
                + "        \"qcs\": [\"qcs::cam::uin/%s:uin/%s\"]"
                + "      },"
                + "      \"Effect\": \"allow\","
                + "      \"Action\": [\"name/cos:GetObject\"],"
                + "      \"Resource\": [\"qcs::cosrapid:%s:uid/%s:%s/*\"]"
                + "    }"
                + "  ],"
                + "  \"Version\": \"2.0\""
                + "}", ownerUin, ownerUin, region, appid, policyBucket);

        try {
            cosclient.setBucketPolicy(policyBucket, policyText);
            Thread.sleep(5000);

            BucketPolicy policy = cosclient.getBucketPolicy(policyBucket);
            assertNotNull(policy.getPolicyText());
            assertFalse(policy.getPolicyText().isEmpty());
            cosclient.deleteBucketPolicy(policyBucket);
            try {
                cosclient.getBucketPolicy(policyBucket);
                fail("Expected NoSuchPolicy after deleting the policy");
            } catch (com.qcloud.cos.exception.CosServiceException e) {
                assertEquals(404, e.getStatusCode());
                assertEquals("NoSuchPolicy", e.getErrorCode());
            }
        } finally {
            cosclient.deleteBucket(policyBucket);
        }
    }

    @Test
    public void testListBucketsForRapidBucket() {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }
        // 验证 buildUrlAndHost 中 isServiceRequest 分支走 buildGetServiceApiEndpoint(bucket)
        List<Bucket> buckets = cosclient.listBuckets();
        assertNotNull(buckets);
        boolean found = false;
        for (Bucket b : buckets) {
            if (b.getName().equals(bucket)) {
                found = true;
                break;
            }
        }
        assertTrue("ListBuckets should contain the rapid bucket", found);
    }
}