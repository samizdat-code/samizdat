"""Hermes with its reasoning effort set: Harbor's adapter has no option for it."""
import yaml
from harbor.agents.installed.hermes import Hermes


class HermesEffort(Hermes):
    @staticmethod
    def _build_config_yaml(*args, **kwargs):
        config = yaml.safe_load(Hermes._build_config_yaml(*args, **kwargs))
        config.setdefault("agent", {})["reasoning_effort"] = "high"
        return yaml.dump(config, default_flow_style=False)
