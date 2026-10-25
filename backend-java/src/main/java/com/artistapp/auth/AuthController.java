package com.artistapp.auth;

import com.artistapp.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record LoginRequest(@NotBlank @Size(max = 255) String user, @NotBlank @Size(max = 240) String password) {}
    public record RegisterRequest(
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 140) String apellidos,
        @NotBlank @Size(max = 100) String usuario,
        @NotBlank @Email @Size(max = 240) String correo,
        @NotBlank @Size(min = 8, max = 240) @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$") String password,
        @NotBlank String password_confirmation,
        Integer id_tipo
    ) {}

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.query(
            "SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia,contrasena FROM usuarios WHERE usuario = ? OR correo = ? LIMIT 1",
            (rs, row) -> {
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", rs.getLong("id"));
                user.put("nombre", rs.getString("nombre"));
                user.put("apellidos", rs.getString("apellidos"));
                user.put("usuario", rs.getString("usuario"));
                user.put("correo", rs.getString("correo"));
                user.put("id_tipo", rs.getInt("id_tipo"));
                user.put("imagen", rs.getString("imagen"));
                user.put("biografia", rs.getString("biografia"));
                user.put("_password", rs.getString("contrasena"));
                return user;
            }, request.user(), request.user()
        );
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Usuario o contraseña incorrectos.");
        Map<String, Object> user = users.get(0);
        String stored = (String) user.remove("_password");
        boolean valid = false;
        if (stored != null && stored.matches("(?i)^[0-9a-f]{32}$")) {
            valid = java.security.MessageDigest.isEqual(stored.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    md5(request.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (valid) {
                jdbc.update("UPDATE usuarios SET contrasena = ? WHERE id = ?", passwordEncoder.encode(request.password()), user.get("id"));
            }
        } else if (stored != null) {
            try { valid = passwordEncoder.matches(request.password(), stored); } catch (IllegalArgumentException ignored) { valid = false; }
        }
        if (!valid) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Usuario o contraseña incorrectos.");
        return authResponse(user);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) {
        if (!request.password().equals(request.password_confirmation())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Las contraseñas no coinciden.");
        }
        if (request.id_tipo() == null || !java.util.Set.of(3, 5, 7).contains(request.id_tipo())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Tipo de usuario no válido.");
        }
        String email = request.correo().trim().toLowerCase();
        String username = request.usuario().trim();
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO usuarios (nombre,apellidos,usuario,correo,contrasena,id_tipo,imagen,biografia) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, request.nombre().trim());
                ps.setString(2, request.apellidos().trim());
                ps.setString(3, username);
                ps.setString(4, email);
                ps.setString(5, passwordEncoder.encode(request.password()));
                ps.setInt(6, request.id_tipo());
                ps.setString(7, "media/no-user.png");
                ps.setString(8, "Aún no tiene");
                return ps;
            }, keyHolder);
            Number id = keyHolder.getKey();
            if (id == null) throw new IllegalStateException("No se pudo recuperar el ID del usuario creado.");
            Map<String, Object> user = jdbc.queryForMap(
                "SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id = ?", id.longValue());
            return authResponse(user);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El nombre de usuario o correo ya está registrado.");
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(org.springframework.security.core.Authentication authentication) {
        long userId = ((Number) authentication.getPrincipal()).longValue();
        Map<String, Object> user = jdbc.queryForMap(
            "SELECT id,nombre,apellidos,usuario,correo,id_tipo,imagen,biografia FROM usuarios WHERE id = ?", userId);
        return Map.of("user", user);
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        // JWT access tokens are stateless; the client should discard its token.
        return Map.of("message", "Sesión cerrada.");
    }

    private Map<String, Object> authResponse(Map<String, Object> user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user", user);
        response.put("token", jwtService.createToken(((Number) user.get("id")).longValue()));
        return response;
    }

    private String md5(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}