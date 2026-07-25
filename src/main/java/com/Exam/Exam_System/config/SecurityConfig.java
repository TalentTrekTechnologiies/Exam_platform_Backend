package com.Exam.Exam_System.config;

import com.Exam.Exam_System.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;


@Configuration
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource, JwtAuthFilter jwtAuthFilter) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            // Safe to disable: the API is stateless and bearer-token authenticated,
            // so there is no session cookie for a cross-site request to ride on.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // Public: the two front doors, plus uploaded branding assets that
                // the login and instructions screens render before any token exists.
                .requestMatchers("/admin/login", "/admin/register").permitAll()
                .requestMatchers("/student/validate").permitAll()
                // Branding for a college's own sign-in page, shown before anyone
                // has signed in. Returns name and logo only.
                .requestMatchers(HttpMethod.GET, "/public/institution/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                // Health probe for the container and load balancer.
                .requestMatchers(HttpMethod.GET, "/health").permitAll()

                // Everything an institution owns.
                .requestMatchers("/admin/**", "/upload/**").hasRole("ADMIN")

                // Everything a candidate touches during a live exam.
                .requestMatchers("/student/**").hasRole("STUDENT")

                // Default deny. Any endpoint added later is locked until someone
                // makes a deliberate decision about who may call it.
                .anyRequest().denyAll())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    write(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                          "Please sign in to continue."))
                .accessDeniedHandler((req, res, e) ->
                    write(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                          "You do not have access to that.")))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** JSON errors, so the client can branch on `code` instead of parsing HTML. */
    private void write(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"status\":" + status
                        + ",\"code\":\"" + code
                        + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
