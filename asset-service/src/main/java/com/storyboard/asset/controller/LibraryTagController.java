package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.CreateTagRequest;
import com.storyboard.asset.client.AssetContracts.TagRef;
import com.storyboard.asset.service.TagService;
import com.storyboard.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/library/tags")
public class LibraryTagController {

    private final TagService tagService;

    public LibraryTagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ApiResponse<List<TagRef>> list(Authentication auth) {
        return ApiResponse.ok(tagService.list(auth.getName()));
    }

    @PostMapping
    public ApiResponse<TagRef> create(Authentication auth, @Valid @RequestBody CreateTagRequest request) {
        CreateTagRequest scoped = new CreateTagRequest(auth.getName(), request.name(), request.color());
        return ApiResponse.ok(tagService.create(scoped));
    }
}
