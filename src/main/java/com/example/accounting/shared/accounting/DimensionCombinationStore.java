package com.example.accounting.shared.accounting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persists immutable, ledger-scoped auxiliary dimension combinations. */
@Component
public class DimensionCombinationStore {

    private static final int MAX_DIMENSIONS = 16;

    private final JdbcTemplate jdbc;

    public DimensionCombinationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolves a new-write combination. Every referenced type and value must currently be active. */
    public Optional<Resolved> resolveActive(UUID ledgerId,
                                            Collection<DimensionCombinationKey.Dimension> dimensions) {
        return resolve(ledgerId, dimensions, true);
    }

    /** Resolves an audit-backed historical combination without requiring currently active members. */
    public Optional<Resolved> resolveHistorical(UUID ledgerId,
                                                Collection<DimensionCombinationKey.Dimension> dimensions) {
        return resolve(ledgerId, dimensions, false);
    }

    private Optional<Resolved> resolve(UUID ledgerId,
                                       Collection<DimensionCombinationKey.Dimension> dimensions,
                                       boolean activeOnly) {
        if (dimensions.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("A dimension combination may contain at most 16 members");
        }
        DimensionCombinationKey.Result key = DimensionCombinationKey.of(dimensions);
        List<Member> members = new ArrayList<>();
        for (DimensionCombinationKey.Dimension dimension : dimensions) {
            Member member = member(ledgerId, dimension, activeOnly).orElse(null);
            if (member == null) {
                return Optional.empty();
            }
            members.add(member);
        }

        UUID candidateId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into dimension_combination (
                    id, ledger_id, kind, canonical_key, dimension_key)
                values (?, ?, 'STRUCTURED', ?, ?)
                on conflict (ledger_id, canonical_key) do nothing
                """, candidateId, ledgerId, key.canonicalKey(), key.dimensionKey());
        UUID combinationId = inserted == 1 ? candidateId : jdbc.queryForObject("""
                select id from dimension_combination
                where ledger_id = ? and canonical_key = ?
                """, UUID.class, ledgerId, key.canonicalKey());
        if (combinationId == null) {
            throw new IllegalStateException("The dimension combination could not be resolved");
        }
        if (inserted == 1) {
            for (Member member : members) {
                jdbc.update("""
                        insert into dimension_combination_member (
                            ledger_id, combination_id, dimension_type_id, dimension_value_id,
                            dimension_type_code, dimension_type_name,
                            dimension_value_code, dimension_value_name)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, ledgerId, combinationId, member.dimensionTypeId(), member.dimensionValueId(),
                        member.dimensionTypeCode(), member.dimensionTypeName(),
                        member.dimensionValueCode(), member.dimensionValueName());
            }
        }
        return Optional.of(new Resolved(combinationId, key.canonicalKey(), key.dimensionKey(),
                List.copyOf(members)));
    }

    private Optional<Member> member(UUID ledgerId, DimensionCombinationKey.Dimension dimension,
                                    boolean activeOnly) {
        return Optional.ofNullable(jdbc.query("""
                select type.id dimension_type_id, value.id dimension_value_id,
                    type.code dimension_type_code, type.name dimension_type_name,
                    value.code dimension_value_code, value.name dimension_value_name
                from dimension_type type
                join dimension_value value
                  on value.ledger_id = type.ledger_id and value.dimension_type_id = type.id
                where type.ledger_id = ? and type.id = ? and value.id = ?
                  and (not ? or (type.status = 'ACTIVE' and value.status = 'ACTIVE'))
                """, result -> result.next() ? new Member(
                result.getObject("dimension_type_id", UUID.class),
                result.getObject("dimension_value_id", UUID.class),
                result.getString("dimension_type_code"), result.getString("dimension_type_name"),
                result.getString("dimension_value_code"), result.getString("dimension_value_name")) : null,
                ledgerId, dimension.dimensionTypeId(), dimension.dimensionValueId(), activeOnly));
    }

    public record Resolved(UUID id, String canonicalKey, String dimensionKey, List<Member> members) {
    }

    public record Member(UUID dimensionTypeId, UUID dimensionValueId,
                         String dimensionTypeCode, String dimensionTypeName,
                         String dimensionValueCode, String dimensionValueName) {
    }
}
