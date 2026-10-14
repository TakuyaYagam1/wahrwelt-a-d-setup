package ru.serega6531.packmate.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.serega6531.packmate.model.CtfService;
import ru.serega6531.packmate.model.FoundPattern;
import ru.serega6531.packmate.model.Pattern;
import ru.serega6531.packmate.model.enums.PatternActionType;
import ru.serega6531.packmate.model.enums.PatternDirectionType;
import ru.serega6531.packmate.model.enums.SubscriptionMessageType;
import ru.serega6531.packmate.model.pojo.PatternCreateDto;
import ru.serega6531.packmate.model.pojo.PatternDto;
import ru.serega6531.packmate.model.pojo.PatternUpdateDto;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;
import ru.serega6531.packmate.repository.PatternRepository;
import ru.serega6531.packmate.exception.PatternValidationException;
import ru.serega6531.packmate.exception.RequestValidationException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PatternService {

    private final PatternRepository repository;
    private final StreamService streamService;
    private final SubscriptionService subscriptionService;

    private final Map<Integer, Pattern> patterns = new ConcurrentHashMap<>();

    @Autowired
    public PatternService(PatternRepository repository,
                          @Lazy StreamService streamService,
                          SubscriptionService subscriptionService) {
        this.repository = repository;
        this.streamService = streamService;
        this.subscriptionService = subscriptionService;
    }

    @PostConstruct
    public void init() {
        repository.findAll().forEach(p -> patterns.put(p.getId(), p));
        log.info("Загружено паттернов: {}", patterns.size());
    }

    public Pattern find(int id) {
        return patterns.get(id);
    }

    public Collection<Pattern> findAll() {
        return patterns.values();
    }

    public Set<FoundPattern> findMatches(byte[] bytes, CtfService service, PatternDirectionType directionType, PatternActionType actionType) {
        final List<Pattern> list = patterns.values().stream()
                .filter(pattern -> pattern.isEnabled() && !pattern.isDeleted())
                .filter(p -> p.getServiceId() == null || p.getServiceId().equals(service.getPort()))
                .filter(p -> p.getActionType() == actionType)
                .filter(p -> p.getDirectionType() == directionType || p.getDirectionType() == PatternDirectionType.BOTH)
                .toList();
        return new PatternMatcher(bytes, list).findMatches();
    }

    public Set<FoundPattern> matchOne(byte[] bytes, Pattern pattern) {
        return new PatternMatcher(bytes, List.of(pattern)).findMatches();
    }

    public void enable(int id, boolean enabled) {
        RequestValidation.requireIdentifier(id, "Идентификатор паттерна");
        final Pattern pattern = find(id);
        if (pattern != null) {
            pattern.setEnabled(enabled);
            final Pattern saved = repository.save(pattern);
            patterns.put(id, saved);

            if (enabled) {
                log.info("Паттерн включен: id={}", id);
                subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.ENABLE_PATTERN, id));
            } else {
                log.info("Паттерн приостановлен: id={}", id);
                subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.DISABLE_PATTERN, id));
            }
        }
    }

    public void delete(int id) {
        RequestValidation.requireIdentifier(id, "Идентификатор паттерна");
        final Pattern pattern = find(id);
        if (pattern != null) {
            pattern.setDeleted(true);
            final Pattern saved = repository.save(pattern);
            patterns.put(id, saved);

            log.info("Паттерн удален: id={}", id);
            subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.SAVE_PATTERN, toDto(saved)));
        }
    }

    @Transactional
    public PatternDto create(PatternCreateDto dto) {
        validateCreate(dto);
        Pattern pattern = fromDto(dto);

        pattern.setEnabled(true);
        pattern.setDeleted(false);
        pattern.setSearchStartTimestamp(System.currentTimeMillis());

        Pattern saved = save(pattern);
        return toDto(saved);
    }

    @Transactional
    public PatternDto update(int id, PatternUpdateDto dto) {
        RequestValidation.requireIdentifier(id, "Идентификатор паттерна");
        if (dto == null) {
            throw new RequestValidationException("Не переданы параметры паттерна.");
        }
        RequestValidation.requireName(dto.getName(), "Название паттерна");
        RequestValidation.requirePatternColor(dto.getColor());
        Pattern pattern = repository.findById(id).orElseThrow();

        pattern.setName(dto.getName());
        pattern.setColor(dto.getColor());

        Pattern saved = save(pattern);
        return toDto(saved);
    }

    private Pattern save(Pattern pattern) {
        try {
            PatternMatcher.compilePattern(pattern);
        } catch (IllegalArgumentException e) {
            String message = pattern.getSearchType() == ru.serega6531.packmate.model.enums.PatternSearchType.REGEX
                    ? "Некорректный шаблон регулярного выражения. Используется RE2/J; lookaround и обратные ссылки не поддерживаются."
                    : "Некорректное значение шаблона. Проверьте текст или шестнадцатеричную запись байтов.";
            throw new PatternValidationException(message, e);
        }

        final Pattern saved = repository.save(pattern);
        patterns.put(saved.getId(), saved);

        log.info("Создан паттерн id={}", saved.getId());
        subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.SAVE_PATTERN, toDto(saved)));

        return saved;
    }

    public void lookBack(int id, int minutes) {
        RequestValidation.requireIdentifier(id, "Идентификатор паттерна");
        RequestValidation.requireLookback(minutes);
        final Pattern pattern = find(id);
        if (pattern != null && pattern.getActionType() == PatternActionType.FIND) {
            long end = pattern.getSearchStartTimestamp();
            long start = end - TimeUnit.MINUTES.toMillis(minutes);

            pattern.setSearchStartTimestamp(start);
            repository.save(pattern);

            log.info("Поиск по паттерну id={} с {} по {}", id,
                    Instant.ofEpochMilli(start), Instant.ofEpochMilli(end));
            streamService.processLookbackPattern(pattern, start, end);
        }
    }

    public Pattern fromDto(PatternCreateDto dto) {
        Pattern pattern = new Pattern();
        pattern.setName(dto.getName());
        pattern.setValue(dto.getValue());
        pattern.setColor(dto.getColor());
        pattern.setSearchType(dto.getSearchType());
        pattern.setDirectionType(dto.getDirectionType());
        pattern.setActionType(dto.getActionType());
        pattern.setServiceId(dto.getServiceId());
        return pattern;
    }

    public PatternDto toDto(Pattern pattern) {
        PatternDto dto = new PatternDto();
        dto.setId(pattern.getId());
        dto.setEnabled(pattern.isEnabled());
        dto.setDeleted(pattern.isDeleted());
        dto.setName(pattern.getName());
        dto.setValue(pattern.getValue());
        dto.setColor(pattern.getColor());
        dto.setSearchType(pattern.getSearchType());
        dto.setDirectionType(pattern.getDirectionType());
        dto.setActionType(pattern.getActionType());
        dto.setServiceId(pattern.getServiceId());
        return dto;
    }

    private void validateCreate(PatternCreateDto dto) {
        if (dto == null) {
            throw new RequestValidationException("Не переданы параметры паттерна.");
        }
        RequestValidation.requireName(dto.getName(), "Название паттерна");
        RequestValidation.requirePatternValue(dto.getValue());
        RequestValidation.requirePatternColor(dto.getColor());
        if (dto.getSearchType() == null || dto.getDirectionType() == null || dto.getActionType() == null) {
            throw new RequestValidationException("Нужно указать способ поиска, направление и действие паттерна.");
        }
        if (dto.getServiceId() != null) {
            RequestValidation.requirePort(dto.getServiceId());
        }
    }
}
