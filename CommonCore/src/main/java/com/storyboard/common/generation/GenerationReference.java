package com.storyboard.common.generation;

/** 一次生成使用的参考素材。 */
public record GenerationReference(String type, String url, String role) {

    public GenerationReference {
        role = role == null || role.isBlank() ? "REFERENCE" : role;
    }
}
