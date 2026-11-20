package com.qcloud.cos.rapid;

import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteBucketRequest;
import com.qcloud.cos.model.HeadBucketRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * 高性能桶创建/删除用例。
 * CreateBucket / DeleteBucket 属于桶级控制面接口，使用基础凭证，连接走 service 域名
 * （service.cosrapid.&lt;region&gt;.myqcloud.com，对应 fusion-proxy）；但 proxy 从 Host 头
 * 解析待操作的桶名，故启用 isDistinguishHost 生成桶域名 Host。本地对接时
 * EndpointResolver.resolveGetServiceApiEndpoint 把 service 域名拨到 proxy。
 * 创桶要求 4 个云资源 header（vpc-id / cidr-block / subnet-id / zone），由 root-server 分配资源。
 */
public class RapidCreateBucketTest extends AbstractRapidCOSClientTest {

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        AbstractRapidCOSClientTest.initRapidCosClient();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        AbstractRapidCOSClientTest.destroyRapidCosClient();
    }

    private static void fillNetworkHeaders(CreateBucketRequest req) {
        req.putCustomRequestHeader("x-cos-vpc-id", "vpc-rapidut");
        req.putCustomRequestHeader("x-cos-cidr-block", "10.0.0.0/24");
        req.putCustomRequestHeader("x-cos-subnet-id", "subnet-rapidut");
        req.putCustomRequestHeader("x-cos-zone", "ap-guangzhou-1");
    }

    @Test
    public void testCreateAndDeleteBucket() {
        skipIfNotRapid();
        if (!judgeUserInfoValid()) {
            return;
        }

        // 复用当前 rapid 桶的 appid，构造一个新的高性能桶名：<name>-x--<appid>
        String newBucket = newRapidBucketName();

        CreateBucketRequest createReq = new CreateBucketRequest(newBucket);
        fillNetworkHeaders(createReq);
        boolean createdBucket = false;
        try {
            Bucket created = cosclient.createBucket(createReq);
            createdBucket = true;
            assertNotNull(created);
            assertEquals(newBucket, created.getName());

            // HeadBucket 确认桶已存在（对象级 endpoint，走网关；仍用桶域名 host）。
            cosclient.headBucket(new HeadBucketRequest(newBucket));
            cosclient.deleteBucket(new DeleteBucketRequest(newBucket));
            createdBucket = false;
            assertFalse("Deleted bucket must not exist", cosclient.doesBucketExist(newBucket));
        } finally {
            if (createdBucket) {
                cosclient.deleteBucket(new DeleteBucketRequest(newBucket));
            }
        }
    }
}