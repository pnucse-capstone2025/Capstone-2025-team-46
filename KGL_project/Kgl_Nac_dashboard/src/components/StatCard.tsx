const StatCard = ({
  label,
  value,
  colorClass = "bg-blue-500",
}: {
  label: string;
  value: number;
  colorClass?: string;
}) => (
  <div
    className={`text-center text-white rounded-md ${colorClass} px-8 py-6 w-full`}
  >
    <div className="text-3xl font-bold">{value}</div>
    <div className="text-sm">{label}</div>
  </div>
);


export default StatCard;
