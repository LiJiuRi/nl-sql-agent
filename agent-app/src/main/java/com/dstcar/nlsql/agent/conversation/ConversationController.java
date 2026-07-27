package com.dstcar.nlsql.agent.conversation;

import com.dstcar.nlsql.agent.auth.JwtFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 会话管理接口(均需 JWT,userId 从 JwtFilter 注入):列表/新建/历史。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository repo;

    public ConversationController(ConversationRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Conversation> list(HttpServletRequest req) {
        return repo.listByUser(userId(req));
    }

    @PostMapping
    public Conversation create(HttpServletRequest req) {
        String userId = userId(req);
        String conversationId = UUID.randomUUID().toString();
        String title = "新会话 " + LocalDate.now();
        repo.create(conversationId, userId, title);
        return new Conversation(conversationId, title, Instant.now());
    }

    @GetMapping("/{id}/messages")
    public List<MessageEntry> messages(@PathVariable String id, HttpServletRequest req) {
        if (!repo.owns(id, userId(req))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或不属于当前用户");
        }
        return repo.messages(id);
    }

    private static String userId(HttpServletRequest req) {
        String uid = (String) req.getAttribute(JwtFilter.USER_ID_ATTR);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return uid;
    }
}
