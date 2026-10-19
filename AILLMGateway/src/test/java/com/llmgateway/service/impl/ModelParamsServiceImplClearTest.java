package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.ModelRouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelParamsServiceImplClearTest {

    @Mock
    private ModelParamsMapper modelParamsMapper;
    @Mock
    private ModelRouteService modelRouteService;
    @Mock
    private ModelCatalogCache catalogCache;
    @InjectMocks
    private ModelParamsServiceImpl service;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void clearFields_setsColumnsToNullExplicitly() throws Exception {
        ModelParams existing = existing();
        when(modelParamsMapper.selectOne(any())).thenReturn(existing);
        when(modelParamsMapper.selectById("p1")).thenReturn(existing);

        ModelParamsRequest req = mapper.readValue("""
                {"modelName":"m","type":"text","clearFields":["paramsSchema","contextWindow"]}
                """, ModelParamsRequest.class);
        service.upsert(req);

        ArgumentCaptor<Wrapper<ModelParams>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(modelParamsMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<ModelParams>) captor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("params_schema"), sqlSet);
        assertTrue(sqlSet.contains("context_window"), sqlSet);
    }

    @Test
    void blankSchema_isTreatedAsClear() throws Exception {
        ModelParams existing = existing();
        when(modelParamsMapper.selectOne(any())).thenReturn(existing);
        when(modelParamsMapper.selectById("p1")).thenReturn(existing);

        ModelParamsRequest req = mapper.readValue("""
                {"modelName":"m","type":"text","paramsSchema":"   "}
                """, ModelParamsRequest.class);
        service.upsert(req);

        ArgumentCaptor<Wrapper<ModelParams>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(modelParamsMapper).update(isNull(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<ModelParams>) captor.getValue()).getSqlSet();
        assertTrue(sqlSet.contains("params_schema"), sqlSet);
    }

    @Test
    void invalidSchema_isRejectedBeforeWrite() throws Exception {
        String schema = """
                {"request":[{"key":"s","type":"enum","options":["a"],"default":"b"}]}
                """;
        ModelParamsRequest req = mapper.readValue(mapper.writeValueAsString(Map.of(
                "modelName", "m",
                "type", "text",
                "paramsSchema", schema)), ModelParamsRequest.class);

        BusinessException error = assertThrows(BusinessException.class, () -> service.upsert(req));
        assertTrue(error.getMessage().contains("request[0].default"), error.getMessage());
    }

    private ModelParams existing() {
        ModelParams existing = new ModelParams();
        existing.setId("p1");
        existing.setModelName("m");
        existing.setType("text");
        existing.setParamsSchema("{\"request\":[]}");
        existing.setContextWindow(1000);
        existing.setSizes("1024x1024");
        return existing;
    }

    @Test
    void clearUnknownField_isIgnored() throws Exception {
        ModelParams existing = existing();
        when(modelParamsMapper.selectOne(any())).thenReturn(existing);
        when(modelParamsMapper.selectById("p1")).thenReturn(existing);

        ModelParamsRequest req = mapper.readValue("""
                {"modelName":"m","type":"text","clearFields":["notAField"]}
                """, ModelParamsRequest.class);
        service.upsert(req);

        // 未知字段不会生成 update wrapper（避免把任意列名拼进 SQL）
        verify(modelParamsMapper, org.mockito.Mockito.never())
                .update(isNull(), any());
    }

    @Test
    void noClearFields_doesNotIssueClearUpdate() throws Exception {
        ModelParams existing = existing();
        when(modelParamsMapper.selectOne(any())).thenReturn(existing);
        when(modelParamsMapper.selectById("p1")).thenReturn(existing);

        ModelParamsRequest req = mapper.readValue("""
                {"modelName":"m","type":"text","sizes":"1536x1024"}
                """, ModelParamsRequest.class);
        service.upsert(req);

        verify(modelParamsMapper, org.mockito.Mockito.never())
                .update(isNull(), any());
        assertTrue(existing.getSizes().equals("1536x1024"));
    }
}
