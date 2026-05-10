package com.oddscanner;

import com.oddscanner.generated.tables.records.BookmakersRecord;
import com.oddscanner.ingestion.IngestionService;
import com.oddscanner.repository.JooqBookmakerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OddScannerApplication {

    private static final Logger log = LoggerFactory.getLogger(OddScannerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OddScannerApplication.class, args);
    }

    @Bean
    public CommandLineRunner demoRunner(JooqBookmakerRepository bookmakerRepo) {
        return args -> {
            System.out.println("--- Запуск тестового запроса через JooqBookmakerRepository ---");
            List<BookmakersRecord> results = bookmakerRepo.findAllEnabled(); // Изменили вызов
            System.out.println("Найдено " + results.size() + " активных букмекеров:");
            for (BookmakersRecord record : results) { // Изменили итерацию
                // record.getId(), record.getCode(), record.getName()
                System.out.println("- ID: " + record.getId() + ", Code: " + record.getCode() + ", Name: " + record.getName());
            }
            System.out.println("--- Завершено ---");
        };
    }


    // Добавим CommandLineRunner для запуска инжестии при старте
    @Bean
    public CommandLineRunner runAfterStartup(IngestionService ingestionService) {
        return args -> {
            log.info("--- Запуск тестовой инжестии при старте приложения ---");
            // Запустим инжестию для обоих букмекеров
            // Это создаст/обновит данные в таблицах outcomes, markets, events и т.д.
            try {
                ingestionService.ingest("fonbet");
            } catch (Exception e) {
                log.error("Ошибка при инжестии Fonbet: ", e);
            }

            try {
                ingestionService.ingest("marathon");
            } catch (Exception e) {
                log.error("Ошибка при инжестии Marathon: ", e);
            }
            log.info("--- Завершено ---");
        };
    }
}