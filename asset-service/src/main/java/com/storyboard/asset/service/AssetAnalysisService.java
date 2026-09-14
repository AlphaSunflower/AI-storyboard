package com.storyboard.asset.service;

import com.storyboard.asset.repository.LibraryRepository;

public interface AssetAnalysisService {

    void analyze(LibraryRepository.AnalysisTask task);
}
