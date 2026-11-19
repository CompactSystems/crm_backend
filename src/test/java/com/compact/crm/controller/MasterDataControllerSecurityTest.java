package com.compact.crm.controller;

import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Role;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.security.JwtService;
import com.compact.crm.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Master-data dropdown lists (industries, products, batteries, lead
 * sources, lost reasons) may be created / renamed / deleted by ADMIN only,
 * enforced on the server. Every authenticated role can still read them to
 * populate dropdowns.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MasterDataControllerSecurityTest {

    private static final String[] ENDPOINTS = {
            "/api/industries", "/api/products", "/api/batteries",
            "/api/lead-sources", "/api/lost-reasons"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ObjectMapper objectMapper;

    private String adminToken;
    private String managerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenFor("ADMIN", 100, "md-admin@test.com", "9200000001");
        managerToken = tokenFor("MANAGER", 50, "md-manager@test.com", "9200000002");
        employeeToken = tokenFor("EMPLOYEE", 10, "md-employee@test.com", "9200000003");
    }

    private String tokenFor(String roleName, int rank, String email, String phone) {
        Role role = roleRepository.findAll().stream()
                .filter(r -> roleName.equals(r.getName()))
                .findFirst()
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).rank(rank).build()));
        Employee employee = employeeRepository.save(Employee.builder()
                .name(roleName + " user").email(email).phone(phone).password("x").role(role).build());
        return "Bearer " + jwtService.generateToken(new UserPrincipal(employee));
    }

    private static String body(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    @Test
    void managerAndEmployee_cannotCreateRenameOrDelete() throws Exception {

        for (String endpoint : ENDPOINTS) {

            // An ADMIN-created row for the others to try to modify.
            String created = mockMvc.perform(post(endpoint)
                            .header("Authorization", adminToken)
                            .contentType("application/json").content(body("RBAC SEED " + endpoint)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long id = objectMapper.readTree(created).get("id").asLong();

            for (String token : new String[]{managerToken, employeeToken}) {

                mockMvc.perform(post(endpoint)
                                .header("Authorization", token)
                                .contentType("application/json").content(body("RBAC DENIED " + endpoint)))
                        .andExpect(status().isForbidden());

                mockMvc.perform(put(endpoint + "/" + id)
                                .header("Authorization", token)
                                .contentType("application/json").content(body("RBAC RENAMED")))
                        .andExpect(status().isForbidden());

                mockMvc.perform(delete(endpoint + "/" + id).header("Authorization", token))
                        .andExpect(status().isForbidden());

                if (!endpoint.equals("/api/lost-reasons")) { // lost reasons have no activate endpoint
                    mockMvc.perform(put(endpoint + "/" + id + "/activate").header("Authorization", token))
                            .andExpect(status().isForbidden());
                }
            }

            // Nothing was created or changed by the denied calls.
            String list = mockMvc.perform(get(endpoint).header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(list).contains("RBAC SEED " + endpoint)
                    .doesNotContain("RBAC DENIED")
                    .doesNotContain("RBAC RENAMED");
        }
    }

    @Test
    void admin_canCreateRenameAndDelete() throws Exception {

        for (String endpoint : ENDPOINTS) {

            String created = mockMvc.perform(post(endpoint)
                            .header("Authorization", adminToken)
                            .contentType("application/json").content(body("ADMIN MADE " + endpoint)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = objectMapper.readTree(created);
            long id = node.get("id").asLong();

            mockMvc.perform(put(endpoint + "/" + id)
                            .header("Authorization", adminToken)
                            .contentType("application/json").content(body("ADMIN RENAMED " + endpoint)))
                    .andExpect(status().isOk());

            mockMvc.perform(delete(endpoint + "/" + id).header("Authorization", adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void unauthenticated_cannotCreate() throws Exception {
        for (String endpoint : ENDPOINTS) {
            mockMvc.perform(post(endpoint).contentType("application/json").content(body("ANON")))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void everyRole_canStillReadTheDropdownLists() throws Exception {
        for (String token : new String[]{adminToken, managerToken, employeeToken}) {
            for (String endpoint : ENDPOINTS) {
                mockMvc.perform(get(endpoint).header("Authorization", token))
                        .andExpect(status().isOk());
            }
        }
    }
}
