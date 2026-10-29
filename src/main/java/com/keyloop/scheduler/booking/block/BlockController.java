package com.keyloop.scheduler.booking.block;

import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dispatchers block and unblock bays and technicians (spec 6.5, 14.4). */
@RestController
@RequestMapping("/api/v1/dealerships/{dealershipId}/resource-blocks")
public class BlockController {

  private final BlockService blocks;

  /** Creates the controller. */
  public BlockController(BlockService blocks) {
    this.blocks = blocks;
  }

  @PostMapping
  ResponseEntity<BlockView> create(
      CallerContext caller, @PathVariable UUID dealershipId, @RequestBody BlockRequest body) {
    DealershipAccess.requireStaff(caller, dealershipId);
    BlockView block = blocks.create(caller, dealershipId, body);
    return ResponseEntity.created(
            URI.create("/api/v1/dealerships/" + dealershipId + "/resource-blocks/" + block.id()))
        .body(block);
  }

  @DeleteMapping("/{blockId}")
  ResponseEntity<Void> remove(
      CallerContext caller, @PathVariable UUID dealershipId, @PathVariable UUID blockId) {
    DealershipAccess.requireStaff(caller, dealershipId);
    blocks.remove(caller, dealershipId, blockId);
    return ResponseEntity.noContent().build();
  }
}
