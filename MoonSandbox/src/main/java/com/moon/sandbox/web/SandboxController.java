package com.moon.sandbox.web;

import com.moon.sandbox.execution.ExecutionResult;
import com.moon.sandbox.execution.SandboxExecutionService;
import com.moon.sandbox.session.FileEntry;
import com.moon.sandbox.session.ReadFileResult;
import com.moon.sandbox.session.SandboxSessionService;
import com.moon.sandbox.session.WriteFileResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/sandbox/{conversationId}")
public class SandboxController {

    private final SandboxSessionService sessions;
    private final SandboxExecutionService execution;

    public SandboxController(SandboxSessionService sessions,
                             SandboxExecutionService execution) {
        this.sessions = sessions;
        this.execution = execution;
    }

    @PostMapping("/files/write")
    public WriteFileResult write(@PathVariable String conversationId,
                                 @Valid @RequestBody WriteFileRequest request) {
        return sessions.write(conversationId, request.path(), request.content(), request.mode());
    }

    @PostMapping("/files/read")
    public ReadFileResult read(@PathVariable String conversationId,
                               @Valid @RequestBody ReadFileRequest request) {
        return sessions.read(conversationId, request.path(), request.offset(), request.maxChars());
    }

    @GetMapping("/files")
    public List<FileEntry> list(@PathVariable String conversationId,
                                @RequestParam(defaultValue = "") String path) {
        return sessions.list(conversationId, path);
    }

    @GetMapping(value = "/files/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String conversationId,
                                           @RequestParam String path) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(sessions.readBytes(conversationId, path));
    }

    @PostMapping("/execute")
    public ExecutionResult execute(@PathVariable String conversationId,
                                   @Valid @RequestBody ExecuteScriptRequest request) {
        return execution.execute(conversationId, request.script(),
                request.args() == null ? List.of() : request.args(),
                request.timeoutSeconds());
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> cleanup(@PathVariable String conversationId) {
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "removed", sessions.cleanup(conversationId)));
    }
}
