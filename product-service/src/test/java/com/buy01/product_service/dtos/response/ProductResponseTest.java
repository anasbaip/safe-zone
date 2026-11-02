package com.buy01.product_service.dtos.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductResponseTest {
    @Test
    void responsesCompareMediaContentAndDoNotRetainMutableArrays() {
        String[] images = {"image"};
        var response = new ProductResponse("id", "name", "description", 5, "owner", images, new String[]{"media"}, 1, null);
        var same = new ProductResponse("id", "name", "description", 5, "owner", new String[]{"image"}, new String[]{"media"}, 1, null);
        images[0] = "changed";
        assertThat(response).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(response.images()).containsExactly("image");
        assertThat(response.toString()).contains("image", "media");
    }

    @Test
    void responseCopiesListsAndPreservesAbsentMedia() {
        var images = new ArrayList<>(List.of("image"));
        var response = new ProductResponse("id", "name", "description", 5, "owner", images, List.of("media"), 1, null);
        images.clear();
        assertThat(response.images()).containsExactly("image");
        var retainedImages = response.images();
        assertThatThrownBy(() -> retainedImages.add("another")).isInstanceOf(UnsupportedOperationException.class);
        var empty = new ProductResponse("id", "name", "description", 5, "owner", (String[]) null, null, 1, null);
        assertThat(empty.images()).isNull();
        assertThat(empty.mediaIds()).isNull();
    }
}
