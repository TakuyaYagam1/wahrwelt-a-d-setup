package ru.serega6531.packmate.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.serega6531.packmate.properties.PackmateProperties;
import ru.serega6531.packmate.model.CtfService;
import ru.serega6531.packmate.model.enums.SubscriptionMessageType;
import ru.serega6531.packmate.model.pojo.ServiceCreateDto;
import ru.serega6531.packmate.model.pojo.ServiceDto;
import ru.serega6531.packmate.model.pojo.ServiceUpdateDto;
import ru.serega6531.packmate.model.pojo.SubscriptionMessage;
import ru.serega6531.packmate.repository.ServiceRepository;
import ru.serega6531.packmate.exception.RequestValidationException;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ServicesService {

    private final ServiceRepository repository;
    private final SubscriptionService subscriptionService;
    private final PcapService pcapService;

    private final InetAddress localIp;

    private final Map<Integer, CtfService> services = new ConcurrentHashMap<>();
    private final ModelMapper modelMapper;

    @Autowired
    public ServicesService(ServiceRepository repository,
                           SubscriptionService subscriptionService,
                           @Lazy PcapService pcapService,
                           ModelMapper modelMapper,
                           PackmateProperties properties) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.pcapService = pcapService;
        this.modelMapper = modelMapper;
        this.localIp = properties.localIp();
    }

    @PostConstruct
    public void init() {
        repository.findAll().forEach(s -> services.put(s.getPort(), s));
        log.info("Загружено служб: {}", services.size());
    }

    public CtfService find(int id) {
        return services.get(id);
    }

    public Optional<CtfService> findService(InetAddress firstIp, int firstPort, InetAddress secondIp, int secondPort) {
        if (firstIp.equals(localIp)) {
            return findByPort(firstPort);
        } else if (secondIp.equals(localIp)) {
            return findByPort(secondPort);
        }

        return Optional.empty();
    }

    private Optional<CtfService> findByPort(int port) {
        return Optional.ofNullable(services.get(port));
    }

    public List<ServiceDto> findAll() {
        return services.values()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public void deleteByPort(int port) {
        RequestValidation.requirePort(port);
        log.info("Удалена служба на порту {}", port);

        services.remove(port);
        repository.deleteById(port);

        subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.DELETE_SERVICE, port));

        updateFilter();
    }

    @Transactional
    public ServiceDto create(ServiceCreateDto dto) {
        if (dto == null) {
            throw new RequestValidationException("Не переданы параметры службы.");
        }
        RequestValidation.requirePort(dto.getPort());
        RequestValidation.requireName(dto.getName(), "Название службы");
        if (repository.existsById(dto.getPort())) {
            throw new RequestValidationException("Служба с таким портом уже существует.");
        }

        CtfService service = fromDto(dto);

        log.info("Добавлена служба на порту {}", service.getPort());

        return save(service);
    }

    @Transactional
    public ServiceDto update(int port, ServiceUpdateDto dto) {
        RequestValidation.requirePort(port);
        if (dto == null) {
            throw new RequestValidationException("Не переданы параметры службы.");
        }
        RequestValidation.requireName(dto.getName(), "Название службы");
        CtfService service = repository.findById(port).orElseThrow();

        log.info("Изменена служба на порту {}", service.getPort());

        modelMapper.map(dto, service);
        service.setPort(port);
        return save(service);
    }

    private ServiceDto save(CtfService service) {
        final CtfService saved = repository.save(service);
        services.put(saved.getPort(), saved);

        subscriptionService.broadcast(new SubscriptionMessage(SubscriptionMessageType.SAVE_SERVICE, toDto(saved)));

        updateFilter();

        return toDto(saved);
    }

    public void updateFilter() {
        pcapService.updateFilter(findAll());
    }

    private ServiceDto toDto(CtfService service) {
        return modelMapper.map(service, ServiceDto.class);
    }

    private CtfService fromDto(ServiceCreateDto dto) {
        return modelMapper.map(dto, CtfService.class);
    }

}
