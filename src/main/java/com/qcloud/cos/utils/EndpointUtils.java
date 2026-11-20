package com.qcloud.cos.utils;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.endpoint.CIPicRegionEndpointBuilder;
import com.qcloud.cos.endpoint.CIRegionEndpointBuilder;
import com.qcloud.cos.endpoint.EndpointBuilder;
import com.qcloud.cos.endpoint.RegionEndpointBuilder;
import com.qcloud.cos.endpoint.UserSpecifiedEndpointBuilder;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.internal.CIPicServiceRequest;
import com.qcloud.cos.internal.CIServiceRequest;
import com.qcloud.cos.internal.CosServiceRequest;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteBucketRequest;
import com.qcloud.cos.model.ListBucketsRequest;
import com.qcloud.cos.region.Region;
import java.util.regex.Pattern;

public class EndpointUtils {

    /** 列桶及高性能桶创删桶使用 service 路由。 */
    public static boolean isServiceRequest(CosServiceRequest request, String bucket) {
        return request instanceof ListBucketsRequest
                || ((request instanceof CreateBucketRequest || request instanceof DeleteBucketRequest)
                    && isRapidBucket(bucket));
    }

    /** 首次请求和重试共用逻辑域名构建，桶名须已补齐 appId。 */
    public static String buildEndpoint(CosServiceRequest request, String bucket,
            ClientConfig config, boolean rapidClient) {
        EndpointBuilder builder = config.getEndpointBuilder();
        String endpoint;
        if (isServiceRequest(request, bucket)) {
            if (builder instanceof RegionEndpointBuilder && bucket != null) {
                endpoint = ((RegionEndpointBuilder) builder).buildGetServiceApiEndpoint(bucket);
            } else if (builder instanceof RegionEndpointBuilder && rapidClient) {
                endpoint = String.format("service.%s.myqcloud.com", Region.formatRapidRegion(config.getRegion()));
            } else {
                endpoint = builder.buildGetServiceApiEndpoint();
            }
        } else if (request instanceof CIPicServiceRequest) {
            endpoint = new CIPicRegionEndpointBuilder(config.getRegion()).buildGeneralApiEndpoint(bucket);
        } else if (request instanceof CIServiceRequest && !(builder instanceof UserSpecifiedEndpointBuilder)) {
            endpoint = new CIRegionEndpointBuilder(config.getRegion()).buildGeneralApiEndpoint(bucket);
        } else {
            endpoint = builder.buildGeneralApiEndpoint(bucket);
        }
        if (endpoint == null) {
            throw new CosClientException("endpoint is null, please check your endpoint builder");
        }
        return endpoint;
    }

    /** 解析连接地址，显式地址始终具有最高优先级。 */
    public static String resolveEndpoint(CosServiceRequest request, String bucket,
            ClientConfig config, String endpoint) {
        String address = isServiceRequest(request, bucket)
                ? config.getEndpointResolver().resolveGetServiceApiEndpoint(endpoint)
                : config.getEndpointResolver().resolveGeneralApiEndpoint(endpoint);
        if (address == null) {
            throw new CosClientException("endpointAddr is null, please check your endpoint resolver");
        }
        return request.getFixedEndpointAddr() != null ? request.getFixedEndpointAddr() : address;
    }

    private EndpointUtils() {
        // 工具类，禁止实例化
    }

    private static final Pattern RAPID_BUCKET_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]*-x--[0-9]+$");

    /**
     * 高性能桶桶名正则：^[a-z0-9][a-z0-9-]*-x--[0-9]+$
     * 例：bucket-x--1253960454
     */
    public static boolean isRapidBucket(String bucketName) {
        return bucketName != null && RAPID_BUCKET_PATTERN.matcher(bucketName).matches();
    }

    /** 提取 endpoint 中桶名后的 suffix。如 bucket.cosrapid.ap-nanjing.myqcloud.com → cosrapid.ap-nanjing.myqcloud.com */
    public static String extractEndpointSuffix(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        int dotIndex = endpoint.indexOf('.');
        if (dotIndex < 0 || dotIndex == endpoint.length() - 1) {
            return null;
        }
        return endpoint.substring(dotIndex + 1);
    }

    /**
     * 从 suffix 中提取 region。如 cosrapid.ap-guangzhou.myqcloud.com → ap-guangzhou
     * 格式：{service}.{region}.myqcloud.com，取第一个点和第二个点之间的部分。
     */
    public static String extractRegionFromSuffix(String suffix) {
        if (suffix == null) {
            return null;
        }
        int firstDot = suffix.indexOf('.');
        if (firstDot < 0 || firstDot == suffix.length() - 1) {
            return null;
        }
        int secondDot = suffix.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return null;
        }
        return suffix.substring(firstDot + 1, secondDot);
    }

}
