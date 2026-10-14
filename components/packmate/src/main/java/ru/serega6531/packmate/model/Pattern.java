package ru.serega6531.packmate.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import ru.serega6531.packmate.model.enums.PatternActionType;
import ru.serega6531.packmate.model.enums.PatternDirectionType;
import ru.serega6531.packmate.model.enums.PatternSearchType;

import java.util.Objects;

@Getter
@Setter
@RequiredArgsConstructor
@ToString
@Entity(name = "pattern")
@GenericGenerator(
        name = "pattern_generator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @Parameter(name = "sequence_name", value = "pattern_seq"),
                @Parameter(name = "initial_value", value = "1"),
                @Parameter(name = "increment_size", value = "1")
        }
)
public class Pattern {

    @Id
    @GeneratedValue(generator = "pattern_generator")
    private Integer id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean deleted = false;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String value;

    @Column(nullable = false)
    private String color;  // Цвет для CSS.

    @Enumerated
    @Column(nullable = false)
    private PatternSearchType searchType;

    @Enumerated
    @Column(nullable = false)
    private PatternDirectionType directionType;

    @Enumerated
    @Column(nullable = false)
    private PatternActionType actionType;

    private Integer serviceId;

    private long searchStartTimestamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Pattern pattern = (Pattern) o;
        return id != null && Objects.equals(id, pattern.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
