package com.storyboard.asset.controller;

import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.GenerationOutputService;
import com.storyboard.asset.service.GroupService;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LibraryGroupControllerTest {

    private final LibraryGroupController controller = new LibraryGroupController(
            mock(GroupService.class),
            mock(AssetCatalogService.class),
            mock(GenerationOutputService.class)
    );

    @Test
    void regularUserCannotReadSnapshotAsAnotherActor() {
        Authentication auth = authentication("attacker", "ROLE_MEMBER");

        assertThatThrownBy(() -> controller.getSnapshot(auth, "snapshot-1", "owner"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    private static Authentication authentication(String userId, String authority) {
        return new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
