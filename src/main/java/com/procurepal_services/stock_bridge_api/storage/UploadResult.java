package com.procurepal_services.stock_bridge_api.storage;

/** Never throws its way back to a caller - see S3ImageService. */
public record UploadResult(boolean success, String url, String failureReason) {

    public static UploadResult success(String url) {
        return new UploadResult(true, url, null);
    }

    public static UploadResult failure(String reason) {
        return new UploadResult(false, null, reason);
    }
}
