package com.audiobook.server.dto;

public class AuthResponse {
    private String token;
    private String username;
    private boolean success;
    private boolean isAdmin;
    private String message;

    public AuthResponse() {
    }

    public AuthResponse(String token, String username, boolean success, String message) {
        this.token = token;
        this.username = username;
        this.success = success;
        this.message = message;
        this.isAdmin = false;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public boolean getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(boolean isAdmin) {
        this.isAdmin = isAdmin;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
