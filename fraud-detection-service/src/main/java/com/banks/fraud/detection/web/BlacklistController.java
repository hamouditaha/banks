package com.banks.fraud.detection.web;

import com.banks.fraud.detection.engine.BlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Admin endpoints to manage the fraud blacklist live, for demoing the fraud engine
 * without redeploying (e.g. blacklist an account then trigger a transfer through it).
 */
@RestController
@RequestMapping("/api/fraud/blacklist")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    @GetMapping
    public Set<String> list() {
        return blacklistService.all();
    }

    @PostMapping("/{accountId}")
    public ResponseEntity<Void> add(@PathVariable String accountId) {
        blacklistService.add(accountId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> remove(@PathVariable String accountId) {
        blacklistService.remove(accountId);
        return ResponseEntity.noContent().build();
    }
}
