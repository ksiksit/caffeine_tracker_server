package com.jongbeom.server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jongbeom.server.domain.auth.JwtTokenProvider;
import com.jongbeom.server.domain.auth.RefreshTokenVerifier;
import com.jongbeom.server.domain.auth.RefreshTokenVerifier.RefreshClaims;
import com.jongbeom.server.domain.auth.dto.LoginRequest;
import com.jongbeom.server.domain.auth.dto.RefreshTokenRequest;
import com.jongbeom.server.domain.auth.dto.SignupRequest;
import com.jongbeom.server.domain.auth.dto.SignupResponse;
import com.jongbeom.server.domain.auth.dto.TokenResponse;
import com.jongbeom.server.domain.auth.exception.DuplicateEmailException;
import com.jongbeom.server.domain.auth.exception.InvalidCredentialsException;
import com.jongbeom.server.domain.auth.exception.InvalidRefreshTokenException;
import com.jongbeom.server.support.UserFixture;
import com.jongbeom.server.domain.user.entity.User;
import com.jongbeom.server.domain.user.repository.UserRepository;
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
    RefreshTokenVerifier refreshTokenVerifier;

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
        given(jwtTokenProvider.createRefreshToken(user))
                .willReturn(new JwtTokenProvider.IssuedToken("refresh.jwt", 1209600));

        TokenResponse response = authService.login(new LoginRequest("a@b.com", "password1!"));

        assertThat(response.accessToken()).isEqualTo("access.jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh.jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.refreshExpiresIn()).isEqualTo(1209600);
    }

    @Test
    void login_존재하지않는이메일이면_InvalidCredentialsException() {
        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password1!")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtTokenProvider, never()).createRefreshToken(any());
    }

    @Test
    void login_비밀번호불일치이면_InvalidCredentialsException() {
        User user = UserFixture.withId(7L);
        given(userRepository.findByEmail("a@b.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtTokenProvider, never()).createRefreshToken(any());
    }

    @Test
    void refresh_성공시_새_AccessToken과_새_RefreshToken을_반환한다() {
        User user = UserFixture.withId(7L);
        given(refreshTokenVerifier.verify("old.refresh.jwt")).willReturn(new RefreshClaims(7L, 0));
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(jwtTokenProvider.createAccessToken(user))
                .willReturn(new JwtTokenProvider.IssuedToken("new.access.jwt", 3600));
        given(jwtTokenProvider.createRefreshToken(user))
                .willReturn(new JwtTokenProvider.IssuedToken("new.refresh.jwt", 1209600));

        TokenResponse response = authService.refresh(new RefreshTokenRequest("old.refresh.jwt"));

        assertThat(response.accessToken()).isEqualTo("new.access.jwt");
        assertThat(response.refreshToken()).isEqualTo("new.refresh.jwt");
        assertThat(response.refreshExpiresIn()).isEqualTo(1209600);
    }

    @Test
    void refresh_사용자가_존재하지않으면_InvalidRefreshTokenException() {
        given(refreshTokenVerifier.verify("old.refresh.jwt")).willReturn(new RefreshClaims(7L, 0));
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("old.refresh.jwt")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_토큰버전이_사용자버전과_다르면_InvalidRefreshTokenException() {
        User user = UserFixture.withId(7L);
        user.invalidateTokens(); // 로그아웃으로 버전 0 → 1
        given(refreshTokenVerifier.verify("old.refresh.jwt")).willReturn(new RefreshClaims(7L, 0));
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("old.refresh.jwt")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(jwtTokenProvider, never()).createAccessToken(any());
    }

    @Test
    void refresh_토큰검증에_실패하면_예외를_그대로_전파하고_사용자를_조회하지_않는다() {
        given(refreshTokenVerifier.verify("bad")).willThrow(new InvalidRefreshTokenException());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("bad")))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void logout_은_사용자의_토큰버전을_올린다() {
        User user = UserFixture.withId(7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));

        authService.logout(7L);

        assertThat(user.getTokenVersion()).isEqualTo(1);
    }

    @Test
    void logout_사용자가_없어도_예외없이_끝난다() {
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        authService.logout(7L);

        verify(userRepository).findById(7L);
    }
}
