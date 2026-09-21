package com.storyboard.asset.service;

import com.storyboard.asset.dto.ImportContracts.ItemResult;
import com.storyboard.asset.dto.ImportContracts.ImportJobStatus;
import com.storyboard.asset.dto.ImportContracts.ScanRequest;
import com.storyboard.asset.dto.ImportContracts.ScanResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ImportService {

    ScanResponse scan(String userId, ScanRequest request);

    ItemResult uploadItem(String userId, String jobId, String itemKey, String confirmationToken, MultipartFile file);

    ImportJobStatus getJob(String userId, String jobId);
}
