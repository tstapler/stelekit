package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class SteleKitRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "stelekit"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            RegexInLambdaRule(config),
            MissingDirectRepositoryWriteRule(config),
            RepositoryWriteCallSiteRule(config),
        ),
    )
}
