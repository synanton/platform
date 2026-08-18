package org.synanton.topology;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal configuration root for test slices (@JdbcTest, etc.). */
@SpringBootApplication(scanBasePackages = "org.synanton.topology")
public class TestConfig {}
