package com.eniglio.ragplatform.rag.service;

import com.eniglio.ragplatform.rag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchServiceTest {

    private final HybridSearchService service =
            new HybridSearchService(null, null, null,
                    new RagProperties(5, 0.5, 15, List.of(), new RagProperties.DocumentInsights(40)));

    @Test
    void documentPresentInBothListsOutranksOnePresentInOnlyOne() {
        Document onlyVector = Document.builder().id("v1").text("t").metadata(Map.of()).build();
        Document onlyText = Document.builder().id("t1").text("t").metadata(Map.of()).build();
        Document inBoth = Document.builder().id("both").text("t").metadata(Map.of()).build();

        List<Document> vectorResults = List.of(onlyVector, inBoth);
        List<Document> textResults = List.of(inBoth, onlyText);

        List<Document> fused = service.fuseWithRrf(vectorResults, textResults, 3);

        assertThat(fused).extracting(Document::getId).containsExactly("both", "v1", "t1");
    }

    @Test
    void computesStandardReciprocalRankFusionScore() {
        Document doc = Document.builder().id("d1").text("t").metadata(Map.of()).build();

        // rank 1 in both lists: 1/(60+1) + 1/(60+1) = 2/61
        List<Document> fused = service.fuseWithRrf(List.of(doc), List.of(doc), 1);

        assertThat(fused.get(0).getScore()).isEqualTo(2.0 / 61.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void documentAbsentFromAListIsNotPenalizedBeyondNotContributing() {
        Document onlyInVector = Document.builder().id("v1").text("t").metadata(Map.of()).build();

        List<Document> fused = service.fuseWithRrf(List.of(onlyInVector), List.of(), 1);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getScore()).isEqualTo(1.0 / 61.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void limitsFusedResultsToTopK() {
        Document a = Document.builder().id("a").text("t").metadata(Map.of()).build();
        Document b = Document.builder().id("b").text("t").metadata(Map.of()).build();
        Document c = Document.builder().id("c").text("t").metadata(Map.of()).build();

        List<Document> fused = service.fuseWithRrf(List.of(a, b, c), List.of(), 2);

        assertThat(fused).hasSize(2);
    }
}
