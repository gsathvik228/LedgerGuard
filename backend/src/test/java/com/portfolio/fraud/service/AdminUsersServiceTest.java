package com.portfolio.fraud.service;
import com.portfolio.fraud.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminUsersServiceTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    AdminUsersService service=new AdminUsersService(db);
    User admin=User.developmentAdmin("admin@example.test","hash");
    @Test void directServiceAccessAlsoRequiresAdmin(){assertThatThrownBy(()->service.list(new User("customer@example.test","hash","Customer"),"",0,20)).hasMessageContaining("Administrator");assertThatThrownBy(()->service.list(null,"",0,20)).hasMessageContaining("Administrator");verifyNoInteractions(db);}
    @Test void invalidPaginationAndOversizedSearchAreRejectedBeforeQuery(){assertThatThrownBy(()->service.list(admin,"",-1,20)).hasMessageContaining("Page");assertThatThrownBy(()->service.list(admin,"",0,101)).hasMessageContaining("Page");assertThatThrownBy(()->service.list(admin,"",0,0)).hasMessageContaining("Page");assertThatThrownBy(()->service.list(admin,"x".repeat(256),0,20)).hasMessageContaining("Search");verifyNoInteractions(db);}
}
