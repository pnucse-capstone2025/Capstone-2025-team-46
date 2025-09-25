import React from "react";
import GaugeWidget from "./GaugeWidget";

interface RiskGaugeProps {
  value: number;
}

const RiskGauge: React.FC<RiskGaugeProps> = ({ value }) => {
  return <GaugeWidget value={value} />;
};

export default RiskGauge;
