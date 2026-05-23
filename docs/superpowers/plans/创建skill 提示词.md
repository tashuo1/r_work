提示词
```



```

优化提示词
```sql
同意，继续开始做。继续，不断精修，只有综合评分达到99分才停止。

当前必须将p0、p1、p2的事情做完，做出最细粒度的拆解，每次改动，就测试下。
考虑场景：10个数据库，每个数据库4000个表进行构建本体论元数据，需要考虑该场景，本体论元数据大模型分析不会中断，且效率可控。
优化步骤如下：

1：大模型驱动本体论建模为第一要义，skill 最小改动原则。
2：优先阅读ontology-modeling-optimization-log.md的问题，并优化改进。并优化ontology-modeling skills，并生效。禁令：这是一个通用型skills，禁止直接将meta库数据复制到meta_gpt,等等相关形式。
3：使用codex 用审计视角查看 ontology-modeling skill 里每个文档的作用，并明确判断是否改的有效，且是在优化该skill.重要审计：1、是否存在“copy meta 元数据到 meta_gpt”的行为。2、审计修改是否符合本体论建模理论。3：审计该skills是否存在通用型，而不是为某个数据库修改。4：其他多个审计视角。存在的话给出修改意见，并审计。

4:只使用skills ontology-modeling 然后测试，本地的mysql数据库从ctsj-rgzn库 解析本体元数据写入到meta_gpt库中。

5：验证本体数据库mysql数据库mate库与meta_gpt库。综合给出评分。

6：验证本体数据库mysql数据库mate库与meta_gpt库。mate库是我自己分析出来的本体论元数据。meta_gpt库是通过skills ontology-modeling 生成出来的数据，是什么原因导致meta_gpt生成的数据少的问题，你深入分析剖解下，从深度挖掘和联想设计等角度考虑呢？后续你的优化方式有哪些？

7：优化skills ontology-modeling ，并让其生效。

8： 每次改动的记录生成md文档发给我看下，记录到同一个文档中。
注意：code等必要、唯一字段为英文或编码，显示字段为中文展示。

```


```
  

1：使用codex 用审计视角查看 ontology-modeling skill 里每个文档及修改的内容，并明确判断是否改的有效，且是在优化该skill.重要审计：1、是否存在“copy meta 元数据到 meta_de”的行为。2、审计修改是否符合本体论建模理论。3：审计该skills是否存在通用型，而不是为某个数据库修改。4：其他多个审计视角。存在的话给出修改意见，并审计。

  

2：验证远程数据库mysql数据库meta_deepseek库与源业务库zzmarket库，根据本体理论与事实数据，综合给出skills评分。

  

3：验证远程数据库mysql数据meta_depseek库。meta_deepseek库是通过skills ontology-modeling 生成出来的数据，是什么原因导致meta_deepseek生成的数据少的问题，你深入分析剖解下，从深度挖掘和联想设计等角度考虑呢？后续你的优化方式有哪些？

  

4：优化skills ontology-modeling ，并让其生效。

5： 每次改动的记录生成md文档发给我看下，记录到同一个文档中。

注意：code等必要、唯一字段为英文或编码，显示字段为中文展示。
```