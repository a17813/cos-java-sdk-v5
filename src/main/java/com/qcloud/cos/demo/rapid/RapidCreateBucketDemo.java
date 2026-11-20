package com.qcloud.cos.demo.rapid;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.RapidCOSCredentialProvider;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteBucketRequest;
import com.qcloud.cos.model.HeadBucketRequest;
import com.qcloud.cos.region.Region;

/**
 * 高性能桶创建/删除示例。
 *
 * CreateBucket / DeleteBucket / HeadBucket 属于桶级控制面接口，使用基础凭证。
 * 创建高性能桶必须携带 4 个云资源 header（vpc-id / cidr-block / subnet-id / zone），
 * 由服务端负责实际的 VPC/子网分配。
 */
public class RapidCreateBucketDemo {
    private static String secretId = "AKIDXXXXXXXX";
    private static String secretKey = "1A2Z3YYYYYYYYYY";
    // 高性能桶名格式：<name>-x--<appid>
    private static String bucketName = "examplebucket-x--12500000000";
    private static String region = "ap-guangzhou";
    private static COSClient cosClient = createClient();

    public static void main(String[] args) {
        try {
            createBucketDemo();
            headBucketDemo();
            deleteBucketDemo();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cosClient.shutdown();
        }
    }

    private static COSClient createClient() {
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        RapidCOSCredentialProvider credProvider = new RapidCOSCredentialProvider(cred);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 建删桶连接 service 地址，Host 根据桶名生成。
        clientConfig.setIsDistinguishHost(true);
        return new COSClient(credProvider, clientConfig);
    }

    private static void createBucketDemo() {
        CreateBucketRequest createBucketRequest = new CreateBucketRequest(bucketName);
        // 创建高性能桶必须携带以下 4 个云资源 header
        createBucketRequest.putCustomRequestHeader("x-cos-vpc-id", "vpc-xxxxxxxx");
        createBucketRequest.putCustomRequestHeader("x-cos-cidr-block", "10.0.0.0/24");
        createBucketRequest.putCustomRequestHeader("x-cos-subnet-id", "subnet-xxxxxxxx");
        createBucketRequest.putCustomRequestHeader("x-cos-zone", "ap-guangzhou-1");

        try {
            Bucket bucket = cosClient.createBucket(createBucketRequest);
            System.out.println("create bucket succeed: " + bucket.getName());
        } catch (CosServiceException e) {
            e.printStackTrace();
        } catch (CosClientException e) {
            e.printStackTrace();
        }
    }

    private static void headBucketDemo() {
        try {
            cosClient.headBucket(new HeadBucketRequest(bucketName));
            System.out.println("bucket exists: " + bucketName);
        } catch (CosServiceException e) {
            e.printStackTrace();
        } catch (CosClientException e) {
            e.printStackTrace();
        }
    }

    private static void deleteBucketDemo() {
        try {
            DeleteBucketRequest deleteBucketRequest = new DeleteBucketRequest(bucketName);
            cosClient.deleteBucket(deleteBucketRequest);
            System.out.println("delete bucket succeed: " + bucketName);
        } catch (CosServiceException e) {
            e.printStackTrace();
        } catch (CosClientException e) {
            e.printStackTrace();
        }
    }
}
