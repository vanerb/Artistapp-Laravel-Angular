package com.artistapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Long userId = jwtService.getUserId(header.substring(7).trim());
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    Integer roleId = jdbcTemplate.queryForObject(
                        "SELECT id_tipo FROM usuarios WHERE id = ?", Integer.class, userId);
                    if (roleId != null) {
                        var auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + roleId)));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}