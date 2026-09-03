package com.antithesis.springhegel.user.web;

import com.antithesis.springhegel.user.RegisteredUser;
import com.antithesis.springhegel.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class RegistrationController {

    private final UserService userService;

    public RegistrationController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<RegisteredUser> register(@RequestBody RegistrationRequest request) {
        RegisteredUser result = userService.register(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
