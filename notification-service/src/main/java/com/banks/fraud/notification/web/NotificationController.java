package com.banks.fraud.notification.web;

import com.banks.fraud.notification.domain.Notification;
import com.banks.fraud.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping("/{accountId}")
    public List<Notification> forAccount(@PathVariable String accountId) {
        return notificationRepository.findByAccountId(accountId);
    }
}
