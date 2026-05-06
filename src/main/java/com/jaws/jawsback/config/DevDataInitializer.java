package com.jaws.jawsback.config;

import com.jaws.jawsback.entity.User;
import com.jaws.jawsback.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DevDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        userRepository.findByUserName("admin").orElseGet(() -> userRepository.save(User.builder()
                .email("admin@jaws.local")
                .userName("admin")
                .passwordHash(passwordEncoder.encode("admin"))
                .role(User.Role.ADMIN)
                .build()));
    }
}
