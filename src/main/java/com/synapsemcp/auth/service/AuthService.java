package com.synapsemcp.auth.service;

import com.synapsemcp.auth.dto.AuthRequests.SigninRequest;
import com.synapsemcp.auth.dto.AuthRequests.SignupRequest;
import com.synapsemcp.auth.dto.AuthResponse;
import com.synapsemcp.auth.entity.Tenant;
import com.synapsemcp.auth.entity.User;
import com.synapsemcp.auth.entity.UserRole;
import com.synapsemcp.auth.repository.TenantRepository;
import com.synapsemcp.auth.repository.UserRepository;
import com.synapsemcp.common.exception.ApiException;
import com.synapsemcp.common.service.AuditLogService;
import com.synapsemcp.document.entity.KnowledgeBase;
import com.synapsemcp.document.repository.KnowledgeBaseRepository;
import com.synapsemcp.security.JwtTokenProvider;
import com.synapsemcp.security.RlsSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AuditLogService auditLogService;
    private final String dummyHash;

    @PersistenceContext
    private EntityManager entityManager;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository,
                       KnowledgeBaseRepository knowledgeBaseRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, AuditLogService auditLogService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.auditLogService = auditLogService;
        // Compared against when the email is unknown so sign-in timing does not reveal which emails exist.
        this.dummyHash = passwordEncoder.encode("synapse-timing-equalizer");
    }

    /** Atomically provisions tenant, user and the tenant's single default Knowledge Base. */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw emailTaken();
        }
        try {
            Tenant tenant = new Tenant();
            tenant.setName(request.fullName().trim());
            tenant = tenantRepository.saveAndFlush(tenant);

            // No tenant was bound when this transaction began; bind it now so RLS admits the KB insert.
            RlsSupport.bindTenant(entityManager, tenant.getId());

            User user = new User();
            user.setTenantId(tenant.getId());
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setFullName(request.fullName().trim());
            user.setRole(UserRole.USER);
            user = userRepository.saveAndFlush(user);

            KnowledgeBase kb = new KnowledgeBase();
            kb.setTenantId(tenant.getId());
            kb = knowledgeBaseRepository.saveAndFlush(kb);

            auditLogService.record(tenant.getId(), user.getId(), "SIGNUP", "USER", user.getId(),
                    Map.of("email", email));
            return response(user, kb);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race on the case-insensitive unique email index.
            throw emailTaken();
        }
    }

    @Transactional
    public AuthResponse signin(SigninRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            // No tenant exists to scope an audit row to; an unknown email leaves nothing to log against.
            passwordEncoder.matches(request.password(), dummyHash);
            throw badCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Independent transaction: the user already exists from a prior commit, and this record
            // must survive the ApiException this method is about to throw.
            auditLogService.recordIndependently(user.getTenantId(), user.getId(), "SIGNIN_FAILURE", "USER",
                    user.getId(), Map.of("email", email));
            throw badCredentials();
        }
        RlsSupport.bindTenant(entityManager, user.getTenantId());
        KnowledgeBase kb = knowledgeBaseRepository.findByTenantId(user.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant " + user.getTenantId() + " has no knowledge base"));
        auditLogService.record(user.getTenantId(), user.getId(), "SIGNIN", "USER", user.getId(),
                Map.of("email", email));
        return response(user, kb);
    }

    private AuthResponse response(User user, KnowledgeBase kb) {
        String token = tokenProvider.generateToken(user.getId(), user.getTenantId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getFullName()),
                kb.getId());
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS);
    }
}
