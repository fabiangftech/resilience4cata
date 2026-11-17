package dev.resilience4cata.nativeimage.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A small shop whose beans are protected by resilience4cata, built as a native image. */
@SpringBootApplication
public class NativeShop {

    public static void main(String[] args) {
        SpringApplication.run(NativeShop.class, args);
    }
}
