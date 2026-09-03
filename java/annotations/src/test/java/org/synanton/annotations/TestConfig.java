package org.synanton.annotations;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal configuration root for test slices (@JdbcTest, etc.). */
@SpringBootApplication(scanBasePackages = "org.synanton.annotations")
public class TestConfig {}
