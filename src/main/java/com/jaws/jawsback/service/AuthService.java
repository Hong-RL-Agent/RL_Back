package com.jaws.jawsback.service;

import com.jaws.jawsback.dto.AuthDto.*;
import com.jaws.jawsback.entity.User;
import com.jaws.jawsback.repository.UserRepository;
import com.jaws.jawsback.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jaws.jawsback.exception.DuplicateEmailException;


@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException("이미 사용 중인 이메일입니다.");
        }

        User user = User.builder()
                .userName(request.userName())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(User.Role.USER)
                .build();

        userRepository.save(user);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return issueTokens(user);
    }

    private AuthResponse issueTokens(User user) {
        String accessToken  = jwtProvider.generateAccessToken(user.getEmail(), user.getRole().name());
        String refreshToken = jwtProvider.generateRefreshToken(user.getEmail());
        return AuthResponse.of(accessToken, refreshToken, user);
    }
}