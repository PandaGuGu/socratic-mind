package com.socraticmind.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.app.review.ReviewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审核记录：写得进、读得回、坏输入被拒。 */
class ReviewStoreTest {

    @TempDir
    Path dir;

    @Test
    void savesAndReloadsByVersion() {
        Path file = dir.resolve("reviews.json");
        ReviewStore store = new ReviewStore(file.toString(), new ObjectMapper());
        store.save("safety", 7, "approved", "李医生 · 精神科", "转介信息位置已确认");

        ReviewStore reloaded = new ReviewStore(file.toString(), new ObjectMapper());
        ReviewStore.Review review = reloaded.find("safety");
        assertEquals(7, review.version());
        assertEquals("approved", review.status());
        assertEquals("李医生 · 精神科", review.reviewer());
        assertTrue(review.reviewedAt() != null);
    }

    @Test
    void rejectsIncompleteSignOff() {
        ReviewStore store = new ReviewStore(dir.resolve("r.json").toString(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class,
                () -> store.save("safety", 1, "approved", " ", null));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("safety", 1, "looks_good", "李医生", null));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("unknown", 1, "approved", "李医生", null));
    }
}
