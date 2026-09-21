package com.storyboard.asset.service.impl;

import com.storyboard.asset.config.AssetProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AssetAnalysisServiceImplTest {

    @Test
    void resolvesAndCachesDefaultVisionModel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AssetAnalysisServiceImpl service = new AssetAnalysisServiceImpl(
                mock(LibraryRepository.class),
                mock(ObjectStorage.class),
                new AssetProperties("", "http://gateway.test", "lg-test", "secret"),
                builder
        );
        server.expect(requestTo("http://gateway.test/v1/models?type=vision"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer lg-test"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":"vision-fallback","is_default":false},
                          {"id":"default-vision","is_default":true}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(service.resolveVisionModel()).isEqualTo("default-vision");
        assertThat(service.resolveVisionModel()).isEqualTo("default-vision");
        server.verify();
    }
}
