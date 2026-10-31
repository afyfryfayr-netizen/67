package sleepy.autobuilder.autobuild.core;

import java.util.Objects;

/**
 * Everything the rule engine needs to know about the position being examined.
 */
public record RuleContext(
        BlockKey block,
        BlockCategory category,
        String schematicName,
        int layer,
        String sessionId,
        long packedPosition
) {
    public RuleContext {
        Objects.requireNonNull(block, "block");
        category = category == null ? BlockCategory.from(block) : category;
        schematicName = schematicName == null ? "" : schematicName;
        sessionId = sessionId == null ? "" : sessionId;
    }

    public RuleContext(BlockKey block, int layer, long packedPosition) {
        this(block, BlockCategory.from(block), "", layer, "", packedPosition);
    }
}
