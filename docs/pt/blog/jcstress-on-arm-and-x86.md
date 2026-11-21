# Testando código lock-free em ARM além de x86

Os testes de concorrência do resilience4cata agora rodam em ARM além de x86 depois de cada merge: os 65 passam nos dois, e a única corrida que eles continuam encontrando acabou se mostrando tão comum em x86 quanto em ARM.

*10 de novembro de 2026 · por [fabiangftech](https://github.com/fabiangftech), autor do resilience4cata*

## Por que só x86 não bastava :id=why-x86-alone-was-not-enough

As policies guardam o seu estado em estruturas lock-free: um circuit breaker troca a sua fase com um compare-and-set, um bulkhead conta vagas em um contador atômico, o budget de retry conta chamadas em contadores distribuídos em faixas (stripes), propositalmente com perdas. Cada um deles usa a ordenação de memória mais fraca que ainda é correta, porque em ARM uma barreira completa custa de duas a três vezes mais do que um acesso acquire ou plain ([benchmarks](../benchmarks.md)).

O [jcstress](https://github.com/openjdk/jcstress) é como verificamos que essas ordenações estão corretas: cada teste roda algumas ações em várias threads milhões de vezes, em muitas disposições de threads, e falha diante de qualquer resultado marcado como proibido. A biblioteca tem 65 deles.

Até agora o CI só os rodava em x86. O x86 é TSO (total store order): ele mantém quase todo par de acessos à memória na ordem do programa, e a única reordenação que permite é uma leitura posterior passar à frente de uma escrita anterior. Então, se alguém enfraquece por engano uma leitura volatile para uma plain, o x86 continua se comportando como se ela estivesse ordenada, e o teste não consegue falhar. O ARM reordena com muito mais liberdade, e é também a arquitetura em que a biblioteca foi ajustada. A única execução em ARM até agora tinha sido feita à mão, no Graviton3, para a release candidate.

## O que o CI roda agora :id=what-ci-runs-now

| Workflow | Quando | Modo | Máquinas |
|---|---|---|---|
| `stress` | a cada push para `main`, e todas as noites | quick | x86 e ARM em paralelo |
| `stress-arm` | semanalmente, e à mão | default (mais forks, execuções mais longas) | ARM |

Os dois rodam nos runners hospedados do GitHub, que são gratuitos para repositórios públicos. A execução semanal tem um workflow próprio para que um push para `main`, que reinicia o `stress`, não possa cancelar uma execução de várias horas.

## Os resultados :id=the-results

Três execuções do `stress` desde a mudança: o pull request, o merge e a noite seguinte. Cada execução, nas duas máquinas:

| | x86 (`ubuntu-24.04`) | ARM (`ubuntu-24.04-arm`) |
|---|---|---|
| Topologia de CPU vista pelo jcstress | 2 núcleos × 2 threads de hardware | 4 núcleos × 1 thread |
| Configurações por execução | 2322 | 1050 |
| Falhas, erros leves, erros graves | 0, 0, 0 | 0, 0, 0 |
| Tempo do job | 59–66 min | 22–24 min |
| Execuções do teste "interesting" | ~206 milhões por execução | ~73 milhões por execução |

O número de configurações difere porque o jcstress experimenta os atores em diferentes disposições sobre a topologia de CPU, e com duas threads de hardware por núcleo há mais disposições para experimentar (inferido das linhas de topologia nos logs, não verificado no código do jcstress). Isso também explica a maior parte da diferença de tempo: o x86 roda mais que o dobro de configurações.

## O único resultado interessante :id=the-one-interesting-result

Um teste é marcado como "interesting" em toda execução, nas duas máquinas: `RetryBudgetStress.CallsAreNeverOvercounted`. É de propósito. O [budget de retry](../policies/retry.md#retry-budget) conta cada chamada para poder permitir retries proporcionais ao tráfego, e conta com uma leitura e uma escrita plain sobre um contador em faixas em vez de uma soma atômica: 3.5 ns por chamada contra 9.7 ns de um compare-and-set no Graviton3 ([Retry budget, 0.3.0](../benchmarks.md#retry-budget-030-2026-10-25-aws-graviton3)). O preço é que duas threads disputando uma faixa podem perder uma chamada. Uma chamada perdida deixa o budget um pouco mais restrito, nunca mais solto.

O teste coloca duas threads em uma faixa, cada uma contando duas chamadas. Quatro chamadas é o resultado normal; duas ou três é a chamada perdida, permitida e reportada como interesting; qualquer coisa acima de quatro, ou um retry contado, seria proibido e quebraria o build. Isso nunca foi visto.

| Execução | x86: execuções que perderam uma chamada | ARM: execuções que perderam uma chamada |
|---|---:|---:|
| Pull request | 6.7 % | 5.5 % |
| Merge | 6.3 % | 7.6 % |
| Noite seguinte | 18.6 % | 8.6 % |

Isso corrige algo que escrevemos antes. Depois da execução no Graviton3 (10.9 % das execuções perderam uma chamada), o [plano até a 1.0](../plan-1.0.md) dizia que a perda era vista com muito mais frequência em ARM do que em x86. Estas execuções não mostram isso: as taxas se sobrepõem, e a mais alta é em x86. Faz sentido quando se olha para o que a corrida precisa. Perder uma chamada não exige reordenação nenhuma, só duas threads que leem o contador antes que qualquer uma delas o escreva de volta, e o TSO não impede isso. A taxa depende de quantas vezes as duas threads se sobrepõem, o que muda com a máquina, a disposição e o ruído de um runner compartilhado.

## O que isto ainda não mostra :id=what-this-does-not-show-yet

- **Que o job de ARM pegaria uma ordenação quebrada.** Uma execução verde não é uma prova. Cada teste de corrida da biblioteca foi verificado por mutação (enfraquecer a proteção, confirmar que o teste falha), mas esses mutantes rodaram em x86 e em Apple M1, não nestes runners. Rodá-los de novo em ARM é o próximo passo, e ainda está em aberto no [plano](../plan-1.0.md#after-10). *Atualização, 12 de novembro de 2026: feito. O ARM não pegou nada que o x86 tenha deixado passar: os mutantes pegos em uma máquina são pegos nas duas, e os que sobrevivem sobrevivem nas duas (detalhes no [plano](../plan-1.0.md#after-10)).*
- **Quanto tempo leva o modo default semanal.** Pelas execuções quick, provavelmente de uma a duas horas em quatro núcleos ARM; o limite é de pouco menos de seis. A primeira execução agendada vai dizer.
- **Nada sobre velocidade.** Estes são testes de correção. Os números de desempenho ainda são só de ARM (Graviton3 e Apple M1); o x86 [ainda não foi medido](../performance.md).

## Rode você mesmo :id=run-them-yourself

```bash
./gradlew :stress:jcstress -Pjcstress="-m quick"                     # what CI runs on every push
./gradlew :stress:jcstress                                           # default mode, as the weekly ARM run
./gradlew :stress:jcstress -Pjcstress="-m quick -t RetryBudget"      # one group
```

O relatório fica em `stress/build/jcstress/results/index.html`. Em uma máquina ARM (Apple Silicon, Graviton, Ampere), o mesmo comando testa as ordenações que o x86 esconde.
