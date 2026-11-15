package com.example.loopcut.users;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.users.UserService.UserProfile;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<UserProfile> me(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(userService.me(userId));
    }

    @PatchMapping
    public ApiResponse<UserProfile> update(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.of(userService.update(userId, body));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        userService.delete(userId);
        return ResponseEntity.noContent().build();
    }
}
