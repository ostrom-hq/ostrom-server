// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

/**
 * The golden session package: one decided workflow and one rule compiled for `acme-api`, signed with the TEST ONLY key
 * derived from a public seed. A server package must be these bytes. It was the TypeScript prototype's
 * (`ostrom-core/compat/vectors/private/package.json`) until the second security audit widened the built-in gate
 * patterns (H3); `build/reports/golden/package.json`, written by the test, is the source of an update.
 */
internal object GoldenPackage {
    /** The seed of the test key. */
    const val SEED_HEX: String = "495b2a7cd62314d9538102e8726b3c280b15071f09d2e6664be11b313e1ba78e"

    /** The compilation time. */
    const val COMPILED_AT: String = "2026-10-30T08:45:00.000Z"

    /** The id of the harness receipt the prototype signed. */
    const val HARNESS_RECEIPT: String = "rcpt_fb02440a-28c4-484e-81ad-8cda105b64d7"

    /** The inputs: repository, server, documents. */
    const val INPUT: String =
        "{\"repo\":\"acme-api\",\"serverUrl\":\"https://acme.example\",\"actor\":\"dev-a\",\"clock\":{\"c" +
        "ompiledAt\":\"2026-10-30T08:45:00.000Z\"},\"env\":{\"OSTROM_PIN\":\"auto\",\"OSTROM_CODE_PUBLISH" +
        "ERS\":\"*\"},\"docs\":{\"rules\":[{\"id\":\"r-commit-style\",\"statement\":\"Commit messages sta" +
        "rt with a verb in the imperative.\",\"output\":\"convention\",\"group\":\"Git\",\"scope\":[\"acm" +
        "e-api\"],\"sources\":[{\"kind\":\"history\",\"ref\":\"acme-api@main\"}],\"strength\":\"required" +
        "\",\"confidence\":0.9,\"status\":\"accepted\",\"foundBy\":\"compat\",\"foundAt\":\"2026-10-30T08" +
        ":00:00.000Z\",\"decidedBy\":\"dev-a\",\"decidedAt\":\"2026-10-30T08:00:00.000Z\",\"reviewBy\":\"" +
        "2027-01-28\"}],\"workflows\":[{\"id\":\"wf-ship\",\"name\":\"Ship acme-api\",\"domain\":\"acme-a" +
        "pi\",\"status\":\"accepted\",\"foundAt\":\"2026-10-30T08:00:00.000Z\",\"sources\":[],\"nodes\":[" +
        "{\"id\":\"wf-ship-start\",\"kind\":\"trigger\",\"label\":\"A change is ready to ship\"},{\"id\":" +
        "\"wf-ship-msg\",\"kind\":\"step\",\"label\":\"Write the commit message the team way\",\"detail\"" +
        ":\"One line, imperative.\",\"ruleIds\":[\"r-commit-style\"]},{\"id\":\"wf-ship-tests\",\"kind\":" +
        "\"check\",\"label\":\"Unit tests pass\",\"run\":{\"command\":\"npm test\",\"on\":{\"classes\":[" +
        "\"push-main\"]},\"timeoutSec\":300}},{\"id\":\"wf-ship-second\",\"kind\":\"agent\",\"label\":\"S" +
        "econd\",\"run\":{\"instructions\":\"Read the diff once more.\"}},{\"id\":\"wf-ship-review\",\"ki" +
        "nd\":\"agent\",\"label\":\"Second look\",\"run\":{\"instructions\":\"Review the diff.\"}},{\"id" +
        "\":\"wf-ship-gate\",\"kind\":\"gate\",\"label\":\"Push to main\",\"approvers\":[\"rev-b\",\"dev-" +
        "a\"],\"actions\":[{\"class\":\"push-main\",\"label\":\"Push or merge into main\",\"patterns\":[]" +
        ",\"repos\":[\"acme-api\"]}]},{\"id\":\"wf-ship-tell\",\"kind\":\"notify\",\"label\":\"Tell the t" +
        "eam\",\"run\":{\"channel\":\"log:releases\",\"message\":\"{dev} pushed to {repo} (session {sessi" +
        "on}).\"}}],\"edges\":[{\"from\":\"wf-ship-start\",\"to\":\"wf-ship-msg\"},{\"from\":\"wf-ship-ms" +
        "g\",\"to\":\"wf-ship-tests\"},{\"from\":\"wf-ship-tests\",\"to\":\"wf-ship-second\"},{\"from\":" +
        "\"wf-ship-second\",\"to\":\"wf-ship-review\"},{\"from\":\"wf-ship-review\",\"to\":\"wf-ship-gate" +
        "\"},{\"from\":\"wf-ship-gate\",\"to\":\"wf-ship-tell\"}]}],\"decisions\":[{\"by\":\"dev-a\",\"su" +
        "bject\":{\"kind\":\"workflow\",\"id\":\"wf-ship\",\"label\":\"Ship acme-api\"},\"action\":\"edit" +
        "\",\"after\":{\"status\":\"accepted\",\"gates\":[\"Push to main: rev-b, dev-a\"],\"runs\":[\"Uni" +
        "t tests pass: runs `npm test`\",\"Tell the team: tells log:releases\"]},\"source\":{\"via\":\"co" +
        "nsole\",\"workflowId\":\"wf-ship\"},\"id\":\"dec_a5015269-ff8c-46a7-80da-3f9789aabf24\",\"at\":" +
        "\"2026-10-30T08:30:00.000Z\",\"receiptId\":\"rcpt_434bce76-7516-44f7-8d13-bfaea9f9fe68\"},{\"by" +
        "\":\"dev-a\",\"subject\":{\"kind\":\"policy\",\"id\":\"operator:rev-b\",\"label\":\"Operator acc" +
        "ount rev-b (local)\"},\"action\":\"edit\",\"before\":{\"account\":null},\"after\":{\"login\":\"r" +
        "ev-b\",\"kind\":\"local\",\"name\":null},\"source\":{\"via\":\"console\"},\"id\":\"dec_f19aec09-" +
        "9655-4462-8098-6784e48a97a4\",\"at\":\"2026-10-30T08:50:00.000Z\",\"receiptId\":\"rcpt_dc0a785d-" +
        "c10f-42e6-8026-4d0a070c6910\"},{\"at\":\"2026-10-30T11:05:00.000Z\",\"by\":\"rev-b\",\"subject\"" +
        ":{\"kind\":\"gate\",\"id\":\"appr_62d52461-ed09-464d-86e5-d7dff4e0d0af\",\"label\":\"dev-a via c" +
        "laude-code: push to the main branch in acme/acme-api\"},\"action\":\"approve\",\"before\":{\"sta" +
        "tus\":\"pending\",\"requestedFrom\":\"rev-b\",\"askedBy\":[\"dev-a\"]},\"after\":{\"status\":\"a" +
        "pproved\"},\"source\":{\"via\":\"console\",\"approvalId\":\"appr_62d52461-ed09-464d-86e5-d7dff4e" +
        "0d0af\",\"workflowId\":\"wf-ship\"},\"id\":\"dec_0c2774fd-1351-44e9-86cf-937edeafb642\"}]}}"

    /** The envelope's version. */
    const val VERSION: String = "p202610300845-09ee0b05"

    /** The envelope's digest (of the payload). */
    const val DIGEST: String = "f50130bf93cc633c96c1fc090f760a37a30692ee86b9c4de5b50bc9fda422061"

    /** The envelope's content digest. */
    const val CONTENT_DIGEST: String = "09ee0b05ad553511ca62422561f0955907a53f48959ce26d0cfea79a877291dd"

    /** The envelope's key id. */
    const val KID: String = "b918e282a924dda0"

    /** The envelope's signature. */
    const val SIGNATURE: String =
        "gUJxoYmoRe8RLdmSH6zntYDloI2bujnFb1iQcMewyDg1D0TvBi62o2+9kf+9ifFysPpfp/FY5a6n2ozpzu38Aw=="

    /** The payload, exactly what is signed. */
    const val PAYLOAD: String =
        "{\"compiledAt\":\"2026-10-30T08:45:00.000Z\",\"compiler\":\"0.1.0\",\"contentDigest\":\"09ee0b05" +
        "ad553511ca62422561f0955907a53f48959ce26d0cfea79a877291dd\",\"context\":\"[Ostrom] Workflows comp" +
        "iled into this session:\\n- Ship acme-api, starts when: A change is ready to ship; skill ostrom-" +
        "wf-ship-acme-api; enforced here: check \\\"Unit tests pass\\\", gate \\\"Push to main\\\", notif" +
        "y \\\"Tell the team\\\"\",\"files\":[{\"content\":\"{\\n  \\\"name\\\": \\\"ostrom\\\",\\n  \\\"" +
        "version\\\": \\\"0.0.0-202610300845-0951daf9\\\",\\n  \\\"description\\\": \\\"Ostrom harness fo" +
        "r acme-api: decided rules, gates and receipts\\\"\\n}\",\"mode\":\"file\",\"path\":\".claude-plu" +
        "gin/plugin.json\",\"sha256\":\"56ee6b1f66e64b2e27044fefd022218b798a9ea8da271f1bf66c96b00097732f" +
        "\"},{\"content\":\"---\\nname: ostrom-second-look\\ndescription: \\\"Second look — step of \\\\" +
        "\\\"Ship acme-api\\\\\\\", compiled by Ostrom.\\\"\\n---\\n\\nReview the diff.\\n\\nWhen you are" +
        " done, call node_done (MCP server \\\"ostrom-workflow\\\") with node \\\"wf-ship-review\\\" and " +
        "a one-line summary.\\n\",\"mode\":\"file\",\"path\":\"agents/ostrom-second-look.md\",\"sha256\":" +
        "\"4d1737b517b67ea0005fc095b8c9043c0ebb0ebb494b132be098e425bb19eebd\"},{\"content\":\"---\\nname:" +
        " ostrom-second\\ndescription: \\\"Second — step of \\\\\\\"Ship acme-api\\\\\\\", compiled by Os" +
        "trom.\\\"\\n---\\n\\nRead the diff once more.\\n\\nWhen you are done, call node_done (MCP server" +
        " \\\"ostrom-workflow\\\") with node \\\"wf-ship-second\\\" and a one-line summary.\\n\",\"mode\"" +
        ":\"file\",\"path\":\"agents/ostrom-second.md\",\"sha256\":\"a8b011aa46c0a26c9bf268b5f0fdd3ff1266" +
        "d1ba055d5320deba24e32e0f4776\"},{\"content\":\"name = \\\"ostrom_second_look\\\"\\ndescription =" +
        " \\\"Second look: step of \\\\\\\"Ship acme-api\\\\\\\", compiled by Ostrom.\\\"\\ndeveloper_ins" +
        "tructions = \\\"Review the diff.\\\\n\\\\nWhen you are done, call node_done (MCP server \\\\\\\"" +
        "ostrom-workflow\\\\\\\") with node \\\\\\\"wf-ship-review\\\\\\\" and a one-line summary.\\\"\\n" +
        "\",\"mode\":\"file\",\"path\":\"codex/agents/ostrom_second_look.toml\",\"sha256\":\"2655d5582321" +
        "d0b7cebf819b77efb19ab4d0642e86009ec638936ebc2e95c934\"},{\"content\":\"name = \\\"ostrom_second" +
        "\\\"\\ndescription = \\\"Second: step of \\\\\\\"Ship acme-api\\\\\\\", compiled by Ostrom.\\\"" +
        "\\ndeveloper_instructions = \\\"Read the diff once more.\\\\n\\\\nWhen you are done, call node_d" +
        "one (MCP server \\\\\\\"ostrom-workflow\\\\\\\") with node \\\\\\\"wf-ship-second\\\\\\\" and a " +
        "one-line summary.\\\"\\n\",\"mode\":\"file\",\"path\":\"codex/agents/ostrom_second.toml\",\"sha2" +
        "56\":\"f45371a47c3f9250405a5c3a26a8b6b62cc711e9a5b9f30691863165114fb048\"},{\"content\":\"<!-- o" +
        "strom:begin (generated by `ostrom sync`, do not edit inside this block) -->\\n## Team harness (O" +
        "strom h202610300845-0951daf9)\\n\\nThis repository (acme-api) works under the rules the team dec" +
        "ided in Ostrom. Digest `0951daf912d47071`.\\n\\nAlways:\\n- Commit messages start with a verb in" +
        " the imperative. (r-commit-style)\\n\\nRules by domain: skills `ostrom-git`. For one file: `rule" +
        "s_for(path)` on the MCP server `ostrom`.\\n\\nActions that need a human yes first (the session p" +
        "auses until the answer):\\n- Push to the main branch: Push to main → rev-b or dev-a\\n\\nEvery s" +
        "ession ends with a signed receipt (rules in force, gates asked, who said yes).\\n<!-- ostrom:end" +
        " -->\\n\",\"mode\":\"file\",\"path\":\"ostrom/AGENTS-block.md\",\"sha256\":\"f4102583e7c475c01e3" +
        "ee049b773ac4bf11cf9f1adf225c3e85dd94b914c3192\"},{\"content\":\"{\\n  \\\"version\\\": \\\"h2026" +
        "10300845-0951daf9\\\",\\n  \\\"digest\\\": \\\"0951daf912d470711d7ff5488b40477b0b7a16d13c7fcaa21" +
        "72e9d77eba28db8\\\",\\n  \\\"repo\\\": \\\"acme-api\\\",\\n  \\\"server\\\": \\\"https://acme.ex" +
        "ample\\\",\\n  \\\"failMode\\\": \\\"closed\\\",\\n  \\\"waitSeconds\\\": 300,\\n  \\\"actions\\" +
        "\": [\\n    {\\n      \\\"class\\\": \\\"push-main\\\",\\n      \\\"label\\\": \\\"Push to the m" +
        "ain branch\\\",\\n      \\\"tools\\\": [\\n        \\\"Bash\\\"\\n      ],\\n      \\\"patterns" +
        "\\\": [\\n        \\\"\\\\\\\\bgit(\\\\\\\\s+(-[cC]\\\\\\\\s*[^\\\\\\\\s-]\\\\\\\\S*|--[a-zA-Z][" +
        "\\\\\\\\w-]*(=\\\\\\\\S+)?|-[abd-zABD-Z]\\\\\\\\w*))*\\\\\\\\s+push\\\\\\\\b\\\",\\n        \\\"" +
        "\\\\\\\\bgh\\\\\\\\s+api\\\\\\\\b[^\\\\\\\\n]*git/refs/heads/(main|master)\\\\\\\\b\\\"\\n      " +
        "],\\n      \\\"keywords\\\": [\\n        \\\"push to main\\\",\\n        \\\"main\\\",\\n       " +
        " \\\"pin\\\",\\n        \\\"production\\\",\\n        \\\"merge\\\"\\n      ],\\n      \\\"gate" +
        "\\\": {\\n        \\\"workflowId\\\": \\\"wf-ship\\\",\\n        \\\"workflowName\\\": \\\"Ship " +
        "acme-api\\\",\\n        \\\"nodeId\\\": \\\"wf-ship-gate\\\",\\n        \\\"label\\\": \\\"Push " +
        "to main\\\",\\n        \\\"approvers\\\": [\\n          \\\"rev-b\\\",\\n          \\\"dev-a\\\"" +
        "\\n        ],\\n        \\\"status\\\": \\\"accepted\\\",\\n        \\\"enforce\\\": true,\\n   " +
        "     \\\"basis\\\": \\\"declared\\\",\\n        \\\"reason\\\": \\\"gate approvers of the workfl" +
        "ow\\\"\\n      }\\n    },\\n    {\\n      \\\"class\\\": \\\"push-tag\\\",\\n      \\\"label\\\"" +
        ": \\\"Push a release tag\\\",\\n      \\\"tools\\\": [\\n        \\\"Bash\\\"\\n      ],\\n     " +
        " \\\"patterns\\\": [\\n        \\\"\\\\\\\\bgit(\\\\\\\\s+(-[cC]\\\\\\\\s*[^\\\\\\\\s-]\\\\\\\\S" +
        "*|--[a-zA-Z][\\\\\\\\w-]*(=\\\\\\\\S+)?|-[abd-zABD-Z]\\\\\\\\w*))*\\\\\\\\s+push\\\\\\\\b\\\",\\" +
        "n        \\\"\\\\\\\\bgh\\\\\\\\s+release\\\\\\\\s+create\\\\\\\\b\\\",\\n        \\\"\\\\\\\\bg" +
        "h\\\\\\\\s+api\\\\\\\\b[^\\\\\\\\n]*git/refs/tags\\\\\\\\b\\\"\\n      ],\\n      \\\"keywords\\" +
        "\": [\\n        \\\"tag\\\",\\n        \\\"release\\\",\\n        \\\"production\\\"\\n      ]," +
        "\\n      \\\"gate\\\": null\\n    },\\n    {\\n      \\\"class\\\": \\\"merge\\\",\\n      \\\"l" +
        "abel\\\": \\\"Merge a pull request\\\",\\n      \\\"tools\\\": [\\n        \\\"Bash\\\",\\n     " +
        "   \\\"mcp\\\"\\n      ],\\n      \\\"patterns\\\": [\\n        \\\"\\\\\\\\bgh\\\\\\\\s+pr\\\\" +
        "\\\\s+merge\\\\\\\\b\\\",\\n        \\\"merge_pull_request\\\",\\n        \\\"\\\\\\\\bgh\\\\\\" +
        "\\s+api\\\\\\\\b[^\\\\\\\\n]*(/pulls/[^\\\\\\\\s/]+/merge\\\\\\\\b|/merges\\\\\\\\b|mergePullReq" +
        "uest)\\\"\\n      ],\\n      \\\"keywords\\\": [\\n        \\\"merge\\\",\\n        \\\"approve" +
        "\\\",\\n        \\\"review\\\"\\n      ],\\n      \\\"gate\\\": null\\n    },\\n    {\\n      \\" +
        "\"class\\\": \\\"deploy\\\",\\n      \\\"label\\\": \\\"Deploy to production\\\",\\n      \\\"to" +
        "ols\\\": [\\n        \\\"Bash\\\"\\n      ],\\n      \\\"patterns\\\": [\\n        \\\"\\\\\\\\b" +
        "helm\\\\\\\\b[^\\\\\\\\n;&|]*\\\\\\\\s(upgrade|install|rollback|uninstall)\\\\\\\\b\\\",\\n     " +
        "   \\\"\\\\\\\\bkubectl\\\\\\\\b[^\\\\\\\\n;&|]*\\\\\\\\s(apply|rollout|set\\\\\\\\s+image|delet" +
        "e|scale|replace|patch)\\\\\\\\b\\\",\\n        \\\"\\\\\\\\bgh\\\\\\\\s+workflow\\\\\\\\s+run\\" +
        "\\\\\\b\\\",\\n        \\\"\\\\\\\\bgh\\\\\\\\s+api\\\\\\\\b[^\\\\\\\\n]*/dispatches\\\\\\\\b\\" +
        "\",\\n        \\\"\\\\\\\\bterraform\\\\\\\\b[^\\\\\\\\n;&|]*\\\\\\\\s(apply|destroy)\\\\\\\\b\\" +
        "\",\\n        \\\"\\\\\\\\b(fly|flyctl)\\\\\\\\b[^\\\\\\\\n;&|]*\\\\\\\\sdeploy\\\\\\\\b\\\",\\n" +
        "        \\\"\\\\\\\\bvercel\\\\\\\\b[^\\\\\\\\n]*--prod\\\",\\n        \\\"\\\\\\\\b(npm|pnpm|ya" +
        "rn|bun)\\\\\\\\b[^\\\\\\\\n;&|]*\\\\\\\\spublish\\\\\\\\b\\\",\\n        \\\"\\\\\\\\bdocker\\\\" +
        "\\\\b[^\\\\\\\\n;&|]*(\\\\\\\\spush\\\\\\\\b|--push\\\\\\\\b)\\\",\\n        \\\"(^|[\\\\\\\\s;&" +
        "|/])deploy(\\\\\\\\.sh)?\\\\\\\\s+(prod|production)\\\\\\\\b\\\"\\n      ],\\n      \\\"keywords" +
        "\\\": [\\n        \\\"deploy\\\",\\n        \\\"production\\\",\\n        \\\"helm\\\",\\n      " +
        "  \\\"rollout\\\",\\n        \\\"pin\\\",\\n        \\\"release\\\"\\n      ],\\n      \\\"gate" +
        "\\\": null\\n    },\\n    {\\n      \\\"class\\\": \\\"migration\\\",\\n      \\\"label\\\": \\" +
        "\"Run or add a database migration\\\",\\n      \\\"tools\\\": [\\n        \\\"Bash\\\",\\n      " +
        "  \\\"Write\\\",\\n        \\\"Edit\\\",\\n        \\\"MultiEdit\\\"\\n      ],\\n      \\\"patt" +
        "erns\\\": [\\n        \\\"\\\\\\\\bflyway\\\\\\\\b[^\\\\\\\\n]*\\\\\\\\bmigrate\\\\\\\\b\\\",\\n" +
        "        \\\"\\\\\\\\bprisma\\\\\\\\s+migrate\\\\\\\\s+deploy\\\\\\\\b\\\",\\n        \\\"\\\\\\" +
        "\\balembic\\\\\\\\s+upgrade\\\\\\\\b\\\",\\n        \\\"\\\\\\\\bliquibase\\\\\\\\b[^\\\\\\\\n]*" +
        "\\\\\\\\bupdate\\\\\\\\b\\\",\\n        \\\"\\\\\\\\b(rails|rake)\\\\\\\\s+db:migrate\\\\\\\\b\\" +
        "\",\\n        \\\"\\\\\\\\bknex\\\\\\\\s+migrate\\\",\\n        \\\"/db/migration/\\\"\\n      ]" +
        ",\\n      \\\"keywords\\\": [\\n        \\\"migration\\\",\\n        \\\"schema\\\",\\n        " +
        "\\\"database\\\",\\n        \\\"flyway\\\"\\n      ],\\n      \\\"gate\\\": null\\n    },\\n    " +
        "{\\n      \\\"class\\\": \\\"mobile-release\\\",\\n      \\\"label\\\": \\\"Release the mobile a" +
        "pp to the stores\\\",\\n      \\\"tools\\\": [\\n        \\\"Bash\\\"\\n      ],\\n      \\\"pat" +
        "terns\\\": [\\n        \\\"\\\\\\\\bfastlane\\\\\\\\b\\\",\\n        \\\"\\\\\\\\beas\\\\\\\\s+(" +
        "submit|build)\\\\\\\\b[^\\\\\\\\n]*production\\\"\\n      ],\\n      \\\"keywords\\\": [\\n     " +
        "   \\\"mobile\\\",\\n        \\\"store\\\",\\n        \\\"fastlane\\\",\\n        \\\"daily rele" +
        "ase\\\",\\n        \\\"app\\\"\\n      ],\\n      \\\"gate\\\": null\\n    },\\n    {\\n      \\" +
        "\"class\\\": \\\"client-message\\\",\\n      \\\"label\\\": \\\"Send a message to a customer\\\"" +
        ",\\n      \\\"tools\\\": [\\n        \\\"mcp\\\"\\n      ],\\n      \\\"patterns\\\": [\\n      " +
        "  \\\"send_validated_message\\\",\\n        \\\"discord_send\\\",\\n        \\\"discord_reply\\" +
        "\",\\n        \\\"chat_postmessage\\\",\\n        \\\"send_email\\\",\\n        \\\"slack_post\\" +
        "\"\\n      ],\\n      \\\"keywords\\\": [\\n        \\\"client\\\",\\n        \\\"customer\\\"," +
        "\\n        \\\"support\\\",\\n        \\\"message\\\",\\n        \\\"reply\\\"\\n      ],\\n    " +
        "  \\\"gate\\\": null\\n    }\\n  ]\\n}\",\"mode\":\"file\",\"path\":\"ostrom/gates.json\",\"sha2" +
        "56\":\"5c1a803119e1b58b6470c5afaa18c96625f4b20485c44e2370d18e192dfd3e15\"},{\"content\":\"#!/bin" +
        "/sh\\n# Ostrom check \\\"Unit tests pass\\\" (Ship acme-api, node wf-ship-tests). Compiled by Os" +
        "trom; run by the launcher in a sandbox,\\n# in the repository, with no network and no secret. Ex" +
        "it 0 = passed.\\nset -e\\nnpm test\\n\",\"mode\":\"executable\",\"path\":\"scripts/s-wf-ship-tes" +
        "ts.sh\",\"sha256\":\"9d3bd5d02336ea7ec70c484ddfa85db071926a7561dd1f5b4283233f30b2288f\"},{\"cont" +
        "ent\":\"---\\nname: ostrom-git\\ndescription: \\\"Team rules for Git in acme-api (1 decided, 0 p" +
        "roposed), from Ostrom. Use before changing acme-api.\\\"\\n---\\n\\n# Git: the team's rules for " +
        "acme-api\\n\\nHarness h202610300845-0951daf9, exported by Ostrom. **MUST** and **SHOULD** rules " +
        "were decided by the team (accepted or edited, each with a signed decision receipt when one exist" +
        "s).\\nRules marked **proposed** were found in the history but nobody decided them yet: follow th" +
        "em when in doubt, never cite them as decided.\\n\\n## Decided\\n\\n- **[MUST]** Commit messages " +
        "start with a verb in the imperative.  \\n  <sub>r-commit-style · digest 79b6ac48e522 · scope: ac" +
        "me-api</sub>\\n\\nAsk `rules_for` (MCP server `ostrom`) for the rules of one path.\\n\",\"mode\"" +
        ":\"file\",\"path\":\"skills/ostrom-git/SKILL.md\",\"sha256\":\"a0e42f0f996f5ad5ad358984bf9daf244" +
        "37427df19f78e17c31c7f709adaf3c6\"},{\"content\":\"---\\nname: ostrom-wf-ship-acme-api\\ndescript" +
        "ion: \\\"How the team ships \\\\\\\"Ship acme-api\\\\\\\" in acme-api: the steps in order, what " +
        "Ostrom runs before an action, who says yes, who is told. From Ostrom.\\\"\\n---\\n\\n# Ship acme" +
        "-api\\n\\nDecided by the team: Ostrom enforces it in this session.\\n\\n## A change is ready to " +
        "ship (trigger)\\n\\n## Write the commit message the team way (step)\\n\\nOne line, imperative.\\" +
        "n\\nRules for this step:\\n- Commit messages start with a verb in the imperative. (r-commit-styl" +
        "e)\\n\\n## Unit tests pass (check)\\n\\nBefore push-main, Ostrom runs `npm test` in a sandbox (n" +
        "o network, no secret). If it fails, the action is refused with the output: fix the cause, never " +
        "skip or weaken the check, then run the same command again.\\n\\n## Second (agent)\\n\\nHand this" +
        " step to a sub-agent where your agent has them: `ostrom-second` in Claude Code; in Codex, spawn_" +
        "agent with agent_type `ostrom_second` (and task_name `ostrom_second`). Otherwise do it yourself." +
        " Then call `node_done` (MCP server \\\"ostrom-workflow\\\") with node \\\"wf-ship-second\\\" and" +
        " a one-line summary of what was really done; never report a sub-agent that did not run.\\n\\nRea" +
        "d the diff once more.\\n\\n## Second look (agent)\\n\\nHand this step to a sub-agent where your " +
        "agent has them: `ostrom-second-look` in Claude Code; in Codex, spawn_agent with agent_type `ostr" +
        "om_second_look` (and task_name `ostrom_second_look`). Otherwise do it yourself. Then call `node_" +
        "done` (MCP server \\\"ostrom-workflow\\\") with node \\\"wf-ship-review\\\" and a one-line summa" +
        "ry of what was really done; never report a sub-agent that did not run.\\n\\nReview the diff.\\n" +
        "\\n## Push to main (gate)\\n\\nA human yes is needed from rev-b or dev-a. When Ostrom refuses th" +
        "e action \\\"waiting for a yes\\\", run the `ostrom wait <id>` command it gives, then the same c" +
        "ommand again. Never work around a gate.\\n\\n## Tell the team (notify)\\n\\nAfter the action, Os" +
        "trom tells releases by itself: do not send the message yourself.\\n\",\"mode\":\"file\",\"path\"" +
        ":\"skills/ostrom-wf-ship-acme-api/SKILL.md\",\"sha256\":\"0fb25eb5ba5b3ad2d56a138fbb43fdf4cc8ac3" +
        "abbcc66e9ab037ecc17a1f4aac\"}],\"format\":\"ostrom.package/1\",\"harness\":{\"digest\":\"0951daf" +
        "912d470711d7ff5488b40477b0b7a16d13c7fcaa2172e9d77eba28db8\",\"receiptId\":\"rcpt_fb02440a-28c4-4" +
        "84e-81ad-8cda105b64d7\",\"version\":\"h202610300845-0951daf9\"},\"hooks\":[{\"classes\":[\"push-" +
        "main\"],\"event\":\"PreToolUse\",\"id\":\"h-wf-ship-tests\",\"kind\":\"check\",\"mode\":\"enforc" +
        "e\",\"nodeId\":\"wf-ship-tests\",\"order\":0,\"patterns\":[\"\\\\bgit(\\\\s+(-[cC]\\\\s*[^\\\\s-" +
        "]\\\\S*|--[a-zA-Z][\\\\w-]*(=\\\\S+)?|-[abd-zABD-Z]\\\\w*))*\\\\s+push\\\\b\",\"\\\\bgh\\\\s+api" +
        "\\\\b[^\\\\n]*git/refs/heads/(main|master)\\\\b\"],\"scriptId\":\"s-wf-ship-tests\",\"tools\":[" +
        "\"Bash\"],\"workflowId\":\"wf-ship\"},{\"classes\":[\"push-main\"],\"event\":\"PreToolUse\",\"id" +
        "\":\"h-wf-ship-gate\",\"kind\":\"gate\",\"mode\":\"enforce\",\"nodeId\":\"wf-ship-gate\",\"order" +
        "\":1,\"patterns\":[\"\\\\bgit(\\\\s+(-[cC]\\\\s*[^\\\\s-]\\\\S*|--[a-zA-Z][\\\\w-]*(=\\\\S+)?|-[" +
        "abd-zABD-Z]\\\\w*))*\\\\s+push\\\\b\",\"\\\\bgh\\\\s+api\\\\b[^\\\\n]*git/refs/heads/(main|maste" +
        "r)\\\\b\"],\"tools\":[\"Bash\",\"mcp\"],\"workflowId\":\"wf-ship\"},{\"classes\":[\"push-main\"]" +
        ",\"event\":\"PostToolUse\",\"id\":\"h-wf-ship-tell\",\"kind\":\"notify\",\"mode\":\"enforce\",\"" +
        "nodeId\":\"wf-ship-tell\",\"notifyId\":\"n-wf-ship-tell\",\"order\":2,\"patterns\":[\"\\\\bgit(" +
        "\\\\s+(-[cC]\\\\s*[^\\\\s-]\\\\S*|--[a-zA-Z][\\\\w-]*(=\\\\S+)?|-[abd-zABD-Z]\\\\w*))*\\\\s+push" +
        "\\\\b\",\"\\\\bgh\\\\s+api\\\\b[^\\\\n]*git/refs/heads/(main|master)\\\\b\"],\"tools\":[\"Bash\"" +
        "],\"workflowId\":\"wf-ship\"}],\"mcp\":{\"servers\":[{\"name\":\"ostrom\",\"path\":\"/api/bridge" +
        "/mcp\"},{\"name\":\"ostrom-workflow\",\"path\":\"/api/compiler/mcp\"}],\"tools\":[{\"description" +
        "\":\"Where this session stands in the compiled workflows\",\"name\":\"workflow_status\",\"server" +
        "\":\"ostrom-workflow\"},{\"description\":\"Report that a workflow step (agent node) is done\",\"" +
        "name\":\"node_done\",\"server\":\"ostrom-workflow\"}]},\"nodes\":[{\"artifacts\":[],\"id\":\"wf-" +
        "ship-start\",\"kind\":\"trigger\",\"label\":\"A change is ready to ship\",\"mode\":\"enforce\"," +
        "\"produces\":[{\"event\":\"SessionStart\",\"note\":\"when the workflow starts: one line of the s" +
        "ession context\",\"type\":\"context\"}],\"workflowId\":\"wf-ship\",\"workflowName\":\"Ship acme-" +
        "api\"},{\"artifacts\":[\"skills/ostrom-wf-ship-acme-api/SKILL.md\"],\"id\":\"wf-ship-msg\",\"kin" +
        "d\":\"step\",\"label\":\"Write the commit message the team way\",\"mode\":\"enforce\",\"produces" +
        "\":[{\"note\":\"instructions and linked rules, loaded when the agent needs them\",\"type\":\"ski" +
        "ll\"}],\"workflowId\":\"wf-ship\",\"workflowName\":\"Ship acme-api\"},{\"artifacts\":[\"skills/o" +
        "strom-wf-ship-acme-api/SKILL.md\",\"scripts/s-wf-ship-tests.sh\",\"hook h-wf-ship-tests\"],\"id" +
        "\":\"wf-ship-tests\",\"kind\":\"check\",\"label\":\"Unit tests pass\",\"mode\":\"enforce\",\"pro" +
        "duces\":[{\"event\":\"PreToolUse\",\"shadow\":false,\"type\":\"hook\"},{\"shadow\":false,\"type" +
        "\":\"script\",\"where\":\"workstation\"}],\"trigger\":{\"classes\":[\"push-main\"],\"from\":\"no" +
        "de\",\"patterns\":[],\"resolved\":[\"\\\\bgit(\\\\s+(-[cC]\\\\s*[^\\\\s-]\\\\S*|--[a-zA-Z][\\\\w" +
        "-]*(=\\\\S+)?|-[abd-zABD-Z]\\\\w*))*\\\\s+push\\\\b\",\"\\\\bgh\\\\s+api\\\\b[^\\\\n]*git/refs/h" +
        "eads/(main|master)\\\\b\"]},\"workflowId\":\"wf-ship\",\"workflowName\":\"Ship acme-api\"},{\"ar" +
        "tifacts\":[\"skills/ostrom-wf-ship-acme-api/SKILL.md\",\"agents/ostrom-second.md\",\"codex/agent" +
        "s/ostrom_second.toml\"],\"id\":\"wf-ship-second\",\"kind\":\"agent\",\"label\":\"Second\",\"mode" +
        "\":\"enforce\",\"produces\":[{\"note\":\"Claude Code, OpenCode and Codex (ostrom_* role); a skil" +
        "l for the other agents\",\"type\":\"subagent\"},{\"note\":\"node_done: the agent reports the rev" +
        "iew, kept in the receipt\",\"type\":\"mcp\"}],\"workflowId\":\"wf-ship\",\"workflowName\":\"Ship" +
        " acme-api\"},{\"artifacts\":[\"skills/ostrom-wf-ship-acme-api/SKILL.md\",\"agents/ostrom-second-" +
        "look.md\",\"codex/agents/ostrom_second_look.toml\"],\"id\":\"wf-ship-review\",\"kind\":\"agent\"" +
        ",\"label\":\"Second look\",\"mode\":\"enforce\",\"produces\":[{\"note\":\"Claude Code, OpenCode " +
        "and Codex (ostrom_* role); a skill for the other agents\",\"type\":\"subagent\"},{\"note\":\"nod" +
        "e_done: the agent reports the review, kept in the receipt\",\"type\":\"mcp\"}],\"workflowId\":\"" +
        "wf-ship\",\"workflowName\":\"Ship acme-api\"},{\"artifacts\":[\"hook h-wf-ship-gate (gate: ostro" +
        "m-gate)\"],\"id\":\"wf-ship-gate\",\"kind\":\"gate\",\"label\":\"Push to main\",\"mode\":\"enfor" +
        "ce\",\"produces\":[{\"event\":\"PreToolUse\",\"shadow\":false,\"type\":\"hook\"},{\"shadow\":fal" +
        "se,\"type\":\"approval\"},{\"type\":\"receipt\"}],\"workflowId\":\"wf-ship\",\"workflowName\":\"" +
        "Ship acme-api\"},{\"artifacts\":[\"notification n-wf-ship-tell\",\"hook h-wf-ship-tell\"],\"id\"" +
        ":\"wf-ship-tell\",\"kind\":\"notify\",\"label\":\"Tell the team\",\"mode\":\"enforce\",\"produce" +
        "s\":[{\"event\":\"PostToolUse\",\"shadow\":false,\"type\":\"hook\"},{\"shadow\":false,\"suggeste" +
        "d\":false,\"type\":\"notify\",\"where\":\"ostrom\"}],\"trigger\":{\"classes\":[\"push-main\"],\"" +
        "from\":\"gate\",\"gateNodeId\":\"wf-ship-gate\",\"patterns\":[],\"resolved\":[\"\\\\bgit(\\\\s+(" +
        "-[cC]\\\\s*[^\\\\s-]\\\\S*|--[a-zA-Z][\\\\w-]*(=\\\\S+)?|-[abd-zABD-Z]\\\\w*))*\\\\s+push\\\\b\"" +
        ",\"\\\\bgh\\\\s+api\\\\b[^\\\\n]*git/refs/heads/(main|master)\\\\b\"]},\"workflowId\":\"wf-ship" +
        "\",\"workflowName\":\"Ship acme-api\"}],\"notifications\":[{\"channel\":\"log:releases\",\"id\":" +
        "\"n-wf-ship-tell\",\"message\":\"{dev} pushed to {repo} (session {session}).\",\"mode\":\"enforc" +
        "e\",\"nodeId\":\"wf-ship-tell\",\"workflowId\":\"wf-ship\"}],\"repo\":\"acme-api\",\"scripts\":[" +
        "{\"approval\":{\"allowed\":true,\"at\":\"2026-10-30T08:30:00.000Z\",\"by\":\"dev-a\",\"decisionI" +
        "d\":\"dec_a5015269-ff8c-46a7-80da-3f9789aabf24\",\"reason\":\"decided by a code publisher\",\"re" +
        "ceiptId\":\"rcpt_434bce76-7516-44f7-8d13-bfaea9f9fe68\"},\"command\":\"npm test\",\"id\":\"s-wf-" +
        "ship-tests\",\"label\":\"Unit tests pass\",\"mode\":\"enforce\",\"network\":false,\"nodeId\":\"w" +
        "f-ship-tests\",\"path\":\"scripts/s-wf-ship-tests.sh\",\"sha256\":\"9d3bd5d02336ea7ec70c484ddfa8" +
        "5db071926a7561dd1f5b4283233f30b2288f\",\"timeoutSec\":300,\"where\":\"workstation\",\"workflowId" +
        "\":\"wf-ship\"}],\"server\":\"https://acme.example\",\"version\":\"p202610300845-09ee0b05\",\"wo" +
        "rkflows\":[{\"decidedBy\":\"dev-a\",\"decisionReceipt\":\"rcpt_434bce76-7516-44f7-8d13-bfaea9f9f" +
        "e68\",\"digest\":\"a0748b64b977b4aa24998f1cdd5406701cec950294e4aa626e5b038a6e1894f4\",\"id\":\"w" +
        "f-ship\",\"name\":\"Ship acme-api\",\"status\":\"accepted\"}]}"
}
