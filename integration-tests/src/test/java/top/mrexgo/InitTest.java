package top.mrexgo;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @author liangjuhong
 * @since 2021/12/23
 */

public class InitTest {

	@Test
	public void testInit() {
		// 类路径获取配置文件
		ApplicationContext applicationContext = new ClassPathXmlApplicationContext("spring.xml");

		// 文件系统路径获取配置文件[绝对路径]
//		ApplicationContext applicationContext = new FileSystemXmlApplicationContext("E:\\idea\\springdemo\\spring.xml");

		// 无配置文件加载容器
//		ApplicationContext applicationContext = new AnnotationConfigApplicationContext("com.xx.jack");

		// springboot 加载容器
//		ApplicationContext applicationContext = new EmbeddedWebApplicationContext();
	}
}
