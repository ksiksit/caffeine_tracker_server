package com.jongbeom.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jongbeom.server.auth.JwtTokenProvider;
import com.jongbeom.server.auth.dto.LoginRequest;
import com.jongbeom.server.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.auth.dto.SignupRequest;
import com.jongbeom.server.auth.dto.SignupResponse;
import com.jongbeom.server.auth.dto.TokenResponse;
import com.jongbeom.server.auth.exception.DuplicateEmailException;
import com.jongbeom.server.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.auth.refresh.RefreshTokenService;
import com.jongbeom.server.auth.refresh.RefreshTokenService.IssuedRefreshToken;
import com.jongbeom.server.auth.refresh.RefreshTokenService.RotationResult;
import com.jongbeom.server.support.UserFixture;
import com.jongbeom.server.user.entity.User;
import com.jongbeom.server.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    JwtTokenProvider jwtTokenProvider;
    @Mock
    RefreshTokenService refreshTokenService;

    @InjectMocks
    AuthService authService;

    @Test
    void signup_성공시_저장된_사용자_정보를_반환한다() {
        SignupRequest req = new SignupRequest("a@b.com", "password1!", "테스터");
        given(userRepository.existsByEmail("a@b.com")).willReturn(false);
        given(passwordEncoder.encode("password1!")).willReturn("hashed");
        given(userRepository.save(any(User.class)))
                .willAnswer(inv -> UserFixture.withId(inv.getArgument(0), 10L));

        SignupResponse response = authService.signup(req);

        assertThat(response.userId()).isEqualTo(10L);
        assertThat(response.email()).isEqualTo("a@b.com");
        assertThat(response.nickname()).isEqualTo("테스터");
    }

    @Test
    void signup_중복이메일이면_DuplicateEmailException() {
        SignupRequest req = new SignupRequest("a@b.com", "password1!", "테스터");
        given(userRepository.existsByEmail("a@b.com")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_성공시_AccessToken과_RefreshToken을_모두_반환한다() {
        User user = UserFixture.withId(7L);
        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("password1!", "hashed")).willReturn(true);
        given(jwtTokenProvider.createAccessToken(user))
                .willReturn(new JwtTokenProvider.IssuedToken("access.jwt", 3600));
        given(refreshTokenService.issue(7L))
                .willReturn(new IssuedRefreshToken("refresh-raw", 1209600));

        TokenResponse response = authService.login(new LoginRequest("a@b.com", "password1!"));

        assertThat(response.accessToken()).isEqualTo("access.jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh-raw");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.refreshExpiresIn()).isEqualTo(1209600);
    }

    @Test
    void login_존재하지않는이메일이면_InvalidCredentialsException() {
        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password1!")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void login_비밀번호불일치이면_InvalidCredentialsException() {
        User user = UserFixture.withId(7L);
        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void refresh_성공시_새_AccessToken과_새_RefreshToken을_반환한다() {
        User user = UserFixture.withId(7L);
        given(refreshTokenService.rotate("old-refresh"))
                .willReturn(new RotationResult(7L, new IssuedRefreshToken("new-refresh", 1209600)));
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(user))
                .willReturn(new JwtTokenProvider.IssuedToken("new.access.jwt", 3600));

        TokenResponse response = authService.refresh(new RefreshTokenRequest("old-refresh"));

        assertThat(response.accessToken()).isEqualTo("new.access.jwt");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refresh_사용자가_존재하지않으면_InvalidRefreshTokenException() {
        given(refreshTokenService.rotate("old-refresh"))
                .willReturn(new RotationResult(7L, new IssuedRefreshToken("new-refresh", 1209600)));
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("old-refresh")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logout_은_RefreshTokenService의_revokeAllForUser를_호출한다() {
        authService.logout(7L);

        verify(refreshTokenService).revokeAllForUser(7L);
    }
}
