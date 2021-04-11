package br.com.caelum.camel;

import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;

@SpringBootApplication
public class Boot {

	@Autowired
	private CamelContext context;

	@PostConstruct
	public void init() throws Exception {
		context.addComponent("activemqspring", ActiveMQComponent.activeMQComponent("tcp://localhost:61616"));

		context.addRoutes(new RouteBuilder() {
			@Override
			public void configure() throws Exception {

				errorHandler(deadLetterChannel("activemqspring:queue:pedidos.DLQ").
						logExhaustedMessageHistory(true).
						maximumRedeliveries(3).
						redeliveryDelay(2000).onRedelivery(new Processor() {
					@Override
					public void process(Exchange exchange) throws Exception {
						int counter = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
						int max = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);
						System.out.println("Redelivery " + counter + " | " + max);
					}
				}));

				from("activemqspring:queue:pedidos").
						routeId("route-pedidos").
						to("validator:pedido.xsd").
						multicast().
						to("direct:soap").
						to("direct:http");

				from("direct:http").
						routeId("rota-pedidos").
						log("${body}").
						setProperty("pedidoId", xpath("/pedido/id/text()")).
						setProperty("clienteId", xpath("/pedido/pagamento/email-titular/text()")).
						split().xpath("/pedido/itens/item").
						log("${id} - ${body}").
						filter().xpath("/item/formato[text()='EBOOK']").
						setProperty("ebookId", xpath("/item/livro/codigo/text()")).
						log("${id} - ${body}").
						marshal().xmljson().
						log("${body}").
						setHeader(Exchange.HTTP_METHOD, HttpMethods.GET).
						setHeader(Exchange.HTTP_QUERY, simple("ebookId=${property.ebookId}&pedidoId=${property.pedidoId}&clienteId=${property.clienteId}")).
						to("http4://localhost:8080/webservices/ebook/item");

				from("direct:soap").
						routeId("rota-soap").
						to("xslt:pedido-para-soap.xslt").
						log("${body}").
						setHeader(Exchange.CONTENT_TYPE, constant("text/xml")).
						to("http4://localhost:8080/webservices/financeiro");
			}
		});

	}

	public static void main(String[] args) {
		SpringApplication.run(Boot.class, args);
	}
}
