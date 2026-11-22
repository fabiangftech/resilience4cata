# Blog

Artigos sobre como o resilience4cata é construído, o que ele mede e como usá-lo bem.

## resilience4cata 1.0.0-RC4: o seu próprio bulkhead não abre mais o circuit breaker :id=resilience4cata-100-rc4-your-own-bulkhead-no-longer-opens-the-breaker

*14 de novembro de 2026*

O piloto encontrou o circuit breaker abrindo por causa do próprio bulkhead cheio do método em uma partida a frio, sem nenhuma falha do backend. Por que isso é uma armadilha, a regra da RC4 e a execução do piloto sobre o código novo. [Ler o artigo](release-candidate-4.md)

## Testando código lock-free em ARM além de x86 :id=testing-lock-free-code-on-arm-as-well-as-x86

*10 de novembro de 2026*

Os testes de concorrência agora rodam em ARM e x86 depois de cada merge: por que só x86 não conseguia pegar uma ordenação de memória enfraquecida, o que três execuções em cada um mostraram e por que a única corrida que eles encontram é igualmente comum em x86. [Ler o artigo](jcstress-on-arm-and-x86.md)

## Hedging quando os hedges competem pelo mesmo serviço :id=hedging-when-the-hedges-compete-for-the-same-service

*5 de novembro de 2026*

Uma simulação de `@Hedge` contra um único serviço compartilhado, com cargas de 30 % a 95 %: quando o hedging corta a cauda, quando ele derruba o serviço e o que o budget, o adaptive limit e o cancelamento mudam. [Ler o artigo](hedging-under-contention.md)

## resilience4cata 1.0.0-RC2: experimente antes da 1.0 :id=resilience4cata-100-rc2-try-it-before-10

*4 de novembro de 2026*

A API está congelada para a 1.0: o que significa uma release candidate aqui, o que mudou no caminho, como experimentá-la e qual feedback ainda pode mudar a 1.0.0. [Ler o artigo](release-candidate.md)

## Quanto custa uma anotação de resiliência no Spring, Micronaut e Quarkus :id=what-a-resilience-annotation-costs-in-spring-micronaut-and-quarkus

*1 de novembro de 2026*

Os beans que cada framework entrega a uma aplicação, medidos contra os aspects e interceptors do Resilience4j e o SmallRye Fault Tolerance, mais os seis casos que o resilience4cata não venceu e o que foi feito a respeito do hedge. [Ler o artigo](framework-annotations.md)

## Um circuit breaker de 1.9 ns: resiliência gerada em tempo de compilação :id=a-19-ns-circuit-breaker-resilience-generated-at-compile-time

*27 de outubro de 2026*

Por que decorators custam algo em cada chamada, o que um annotation processor escreve no lugar deles e como o resilience4cata se compara com o Resilience4j 2.4.0, com empates e ressalvas incluídos. [Ler o artigo](compile-time-resilience.md)
