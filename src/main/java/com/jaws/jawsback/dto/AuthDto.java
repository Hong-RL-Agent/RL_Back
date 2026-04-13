package com.jaws.jawsback.dto;

import com.jaws.jawsback.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDto {

    // 회원가입 요청 - POST /api/auth/signup
    public record SignupRequest(
            @NotBlank(message = "이름은 필수입니다.")
            @Size(min = 2, max = 50, message = "이름은 2~50자여야 합니다.")
            String userName,

            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다.")
            String password
    ) {}

    // 로그인 요청 - POST /api/auth/login
    public record LoginRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            String password
    ) {}

    // 인증 성공 응답
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            Long userId,
            String userName,
            String role
    ) {
        public static AuthResponse of(String accessToken, String refreshToken, User user) {
            return new AuthResponse(
                    accessToken,
                    refreshToken,
                    "Bearer",
                    user.getId(),
                    user.getUserName(),
                    user.getRole().name()
            );
        }
    }

    // 에러 응답
    public record ErrorResponse(int status, String message) {}
}