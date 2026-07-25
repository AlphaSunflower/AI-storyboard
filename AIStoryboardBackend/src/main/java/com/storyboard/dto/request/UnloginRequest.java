package com.storyboard.dto.request;

public record UnloginRequest(
    String account,
    String password,
    String jwt
) {}
