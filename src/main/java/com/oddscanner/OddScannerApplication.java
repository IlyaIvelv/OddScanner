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

//    @Bean
//    public CommandLineRunner demoRunner(JooqBookmakerRepository bookmakerRepo, IngestionService ingestionService) {
//        return args -> {
//            System.out.println("--- Статус букмекеров ---");
//
//            // Выводим статус через IngestionService
//            ingestionService.printBookmakersStatus();
//
//            System.out.println("--- Завершено ---");
//        };
//    }


    // Добавим CommandLineRunner для запуска инжестии при старте
    @Bean
    public CommandLineRunner runAfterStartup(IngestionService ingestionService) {
        return args -> {
            log.info("--- Запуск тестовой инжестии при старте приложения ---");


            try {
                log.info("=== НАЧАЛО ЗАГРУЗКИ FONBET ===");
                ingestionService.ingest("fonbet");
                log.info("=== ЗАГРУЗКА FONBET ЗАВЕРШЕНА ===");
            } catch (Exception e) {
                log.error("Ошибка при инжестии Fonbet: ", e);
            }

            try {
                log.info("=== НАЧАЛО ЗАГРУЗКИ MARATHON ===");
                ingestionService.ingest("marathon");
                log.info("=== ЗАГРУЗКА MARATHON ЗАВЕРШЕНА ===");
            } catch (Exception e) {
                log.error("Ошибка при инжестии Marathon: ", e);
            }


            try {
                log.info("=== НАЧАЛО ЗАГРУЗКИ THESPORTSDB ===");
                ingestionService.ingest("thesportsdb");
                log.info("=== ЗАГРУЗКА THESPORTSDB ЗАВЕРШЕНА ===");
            } catch (Exception e) {
                log.error("Ошибка при инжестии TheSportsDB: ", e);
            }

            log.info("--- ЗАВЕРШЕНО ---");
        };
    }
}