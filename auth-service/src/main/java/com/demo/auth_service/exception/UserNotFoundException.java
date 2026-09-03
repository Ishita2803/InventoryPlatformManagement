package com.demo.auth_service.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String username) {
        super("No such user: " + username);
    }
}
